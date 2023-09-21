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

package com.android.server.backup.utils;

import android.app.backup.BackupAnnotations;
import android.app.backup.BackupManagerMonitor;
import android.app.backup.BackupRestoreEventLogger;
import android.os.Bundle;
import android.os.Environment;
import android.util.Slog;

import com.android.internal.util.FastPrintWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;


/*
 * Util class to parse a BMM event and write it to a text file, to be the printed in
 * the backup dumpsys
 *
 * Note: this class is note thread safe
 */
public class BackupManagerMonitorDumpsysUtils {

    private static final String TAG = "BackupManagerMonitorDumpsysUtils";
    // Name of the subdirectory where the text file containing the BMM events will be stored.
    // Same as {@link UserBackupManagerFiles}
    private static final String BACKUP_PERSISTENT_DIR = "backup";

    /**
     * Parses the BackupManagerMonitor bundle for a RESTORE event in a series of strings that
     * will be persisted in a text file and printed in the dumpsys.
     *
     * If the evenntBundle passed is not a RESTORE event, return early
     *
     * Key information related to the event:
     * - Timestamp (HAS TO ALWAYS BE THE FIRST LINE OF EACH EVENT)
     * - Event ID
     * - Event Category
     * - Operation type
     * - Package name (can be null)
     * - Agent logs (if available)
     *
     * Example of formatting:
     * RESTORE Event: [2023-08-18 17:16:00.735] Agent - Agent logging results
     *     Package name: com.android.wallpaperbackup
     *     Agent Logs:
     *         Data Type: wlp_img_system
     *             Item restored: 0/1
     *             Agent Error - Category: no_wallpaper, Count: 1
     *         Data Type: wlp_img_lock
     *             Item restored: 0/1
     *             Agent Error - Category: no_wallpaper, Count: 1
     */
    public void parseBackupManagerMonitorRestoreEventForDumpsys(Bundle eventBundle) {
        if (eventBundle == null) {
            return;
        }

        if (!isOpTypeRestore(eventBundle)) {
            //We only log Restore events
            return;
        }

        if (!eventBundle.containsKey(BackupManagerMonitor.EXTRA_LOG_EVENT_ID)
                || !eventBundle.containsKey(BackupManagerMonitor.EXTRA_LOG_EVENT_CATEGORY)) {
            Slog.w(TAG, "Event id and category are not optional fields.");
            return;
        }
        File bmmEvents = getBMMEventsFile();

        try (FileOutputStream out = new FileOutputStream(bmmEvents, /*append*/ true);
            PrintWriter pw = new FastPrintWriter(out);) {

            int eventCategory = eventBundle.getInt(BackupManagerMonitor.EXTRA_LOG_EVENT_CATEGORY);
            int eventId = eventBundle.getInt(BackupManagerMonitor.EXTRA_LOG_EVENT_ID);

            if (eventId == BackupManagerMonitor.LOG_EVENT_ID_AGENT_LOGGING_RESULTS &&
                    !hasAgentLogging(eventBundle)) {
                // Do not record an empty agent logging event
                return;
            }

            pw.println("RESTORE Event: [" + timestamp() + "] " +
                    getCategory(eventCategory) + " - " +
                    getId(eventId));

            if (eventBundle.containsKey(BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME)) {
                pw.println("\tPackage name: "
                        + eventBundle.getString(BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME));
            }

            // TODO(b/296818666): add extras to the events
            addAgentLogsIfAvailable(eventBundle, pw);
        } catch (java.io.IOException e) {
            Slog.e(TAG, "IO Exception when writing BMM events to file: " + e);
        }

    }

    private boolean hasAgentLogging(Bundle eventBundle) {
        if (eventBundle.containsKey(BackupManagerMonitor.EXTRA_LOG_AGENT_LOGGING_RESULTS)) {
            ArrayList<BackupRestoreEventLogger.DataTypeResult> agentLogs =
                    eventBundle.getParcelableArrayList(
                            BackupManagerMonitor.EXTRA_LOG_AGENT_LOGGING_RESULTS);

            return !agentLogs.isEmpty();
        }
        return false;
    }

    /**
     * Extracts agent logs from the BackupManagerMonitor event. These logs detail:
     * - the data type for the agent
     * - the count of successfully restored items
     * - the count of items that failed to restore
     * - the metadata associated with this datatype
     * - any errors
     */
    private void addAgentLogsIfAvailable(Bundle eventBundle, PrintWriter pw) {
        if (hasAgentLogging(eventBundle)) {
            pw.println("\tAgent Logs:");
            ArrayList<BackupRestoreEventLogger.DataTypeResult> agentLogs =
                    eventBundle.getParcelableArrayList(
                            BackupManagerMonitor.EXTRA_LOG_AGENT_LOGGING_RESULTS);
            for (BackupRestoreEventLogger.DataTypeResult result : agentLogs) {
                int totalItems = result.getFailCount() + result.getSuccessCount();
                pw.println("\t\tData Type: " + result.getDataType());
                pw.println("\t\t\tItem restored: " + result.getSuccessCount() + "/" +
                        totalItems);
                for (Map.Entry<String, Integer> entry : result.getErrors().entrySet()) {
                    pw.println("\t\t\tAgent Error - Category: " +
                            entry.getKey() + ", Count: " + entry.getValue());
                }
            }
        }
    }

    /*
     * Get the path of the text files which stores the BMM events
     */
    public File getBMMEventsFile() {
        File dataDir = new File(Environment.getDataDirectory(), BACKUP_PERSISTENT_DIR);
        File fname = new File(dataDir, "bmmevents.txt");
        return fname;
    }

    private String timestamp() {
        long currentTime = System.currentTimeMillis();
        Date date = new Date(currentTime);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        return dateFormat.format(date);
    }

    private String getCategory(int code) {
        String category = switch (code) {
            case BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT -> "Transport";
            case BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT -> "Agent";
            case BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY ->
                    "Backup Manager Policy";
            default -> "Unknown category code: " + code;
        };
        return category;
    }

    private String getId(int code) {
        String id = switch (code) {
            case BackupManagerMonitor.LOG_EVENT_ID_FULL_BACKUP_CANCEL -> "Full backup cancel";
            case BackupManagerMonitor.LOG_EVENT_ID_ILLEGAL_KEY -> "Illegal key";
            case BackupManagerMonitor.LOG_EVENT_ID_NO_DATA_TO_SEND -> "No data to send";
            case BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_INELIGIBLE -> "Package ineligible";
            case BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_KEY_VALUE_PARTICIPANT ->
                    "Package key-value participant";
            case BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_STOPPED -> "Package stopped";
            case BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_NOT_FOUND -> "Package not found";
            case BackupManagerMonitor.LOG_EVENT_ID_BACKUP_DISABLED -> "Backup disabled";
            case BackupManagerMonitor.LOG_EVENT_ID_DEVICE_NOT_PROVISIONED ->
                    "Device not provisioned";
            case BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_TRANSPORT_NOT_PRESENT ->
                    "Package transport not present";
            case BackupManagerMonitor.LOG_EVENT_ID_ERROR_PREFLIGHT -> "Error preflight";
            case BackupManagerMonitor.LOG_EVENT_ID_QUOTA_HIT_PREFLIGHT -> "Quota hit preflight";
            case BackupManagerMonitor.LOG_EVENT_ID_EXCEPTION_FULL_BACKUP -> "Exception full backup";
            case BackupManagerMonitor.LOG_EVENT_ID_KEY_VALUE_BACKUP_CANCEL ->
                    "Key-value backup cancel";
            case BackupManagerMonitor.LOG_EVENT_ID_NO_RESTORE_METADATA_AVAILABLE ->
                    "No restore metadata available";
            case BackupManagerMonitor.LOG_EVENT_ID_NO_PM_METADATA_RECEIVED ->
                    "No PM metadata received";
            case BackupManagerMonitor.LOG_EVENT_ID_PM_AGENT_HAS_NO_METADATA ->
                    "PM agent has no metadata";
            case BackupManagerMonitor.LOG_EVENT_ID_LOST_TRANSPORT -> "Lost transport";
            case BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_NOT_PRESENT -> "Package not present";
            case BackupManagerMonitor.LOG_EVENT_ID_RESTORE_VERSION_HIGHER ->
                    "Restore version higher";
            case BackupManagerMonitor.LOG_EVENT_ID_APP_HAS_NO_AGENT -> "App has no agent";
            case BackupManagerMonitor.LOG_EVENT_ID_SIGNATURE_MISMATCH -> "Signature mismatch";
            case BackupManagerMonitor.LOG_EVENT_ID_CANT_FIND_AGENT -> "Can't find agent";
            case BackupManagerMonitor.LOG_EVENT_ID_KEY_VALUE_RESTORE_TIMEOUT ->
                    "Key-value restore timeout";
            case BackupManagerMonitor.LOG_EVENT_ID_RESTORE_ANY_VERSION -> "Restore any version";
            case BackupManagerMonitor.LOG_EVENT_ID_VERSIONS_MATCH -> "Versions match";
            case BackupManagerMonitor.LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER ->
                    "Version of backup older";
            case BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE_SIGNATURE_MISMATCH ->
                    "Full restore signature mismatch";
            case BackupManagerMonitor.LOG_EVENT_ID_SYSTEM_APP_NO_AGENT -> "System app no agent";
            case BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE_ALLOW_BACKUP_FALSE ->
                    "Full restore allow backup false";
            case BackupManagerMonitor.LOG_EVENT_ID_APK_NOT_INSTALLED -> "APK not installed";
            case BackupManagerMonitor.LOG_EVENT_ID_CANNOT_RESTORE_WITHOUT_APK ->
                    "Cannot restore without APK";
            case BackupManagerMonitor.LOG_EVENT_ID_MISSING_SIGNATURE -> "Missing signature";
            case BackupManagerMonitor.LOG_EVENT_ID_EXPECTED_DIFFERENT_PACKAGE ->
                    "Expected different package";
            case BackupManagerMonitor.LOG_EVENT_ID_UNKNOWN_VERSION -> "Unknown version";
            case BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE_TIMEOUT -> "Full restore timeout";
            case BackupManagerMonitor.LOG_EVENT_ID_CORRUPT_MANIFEST -> "Corrupt manifest";
            case BackupManagerMonitor.LOG_EVENT_ID_WIDGET_METADATA_MISMATCH ->
                    "Widget metadata mismatch";
            case BackupManagerMonitor.LOG_EVENT_ID_WIDGET_UNKNOWN_VERSION ->
                    "Widget unknown version";
            case BackupManagerMonitor.LOG_EVENT_ID_NO_PACKAGES -> "No packages";
            case BackupManagerMonitor.LOG_EVENT_ID_TRANSPORT_IS_NULL -> "Transport is null";
            case BackupManagerMonitor.LOG_EVENT_ID_TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED ->
                    "Transport non-incremental backup required";
            case BackupManagerMonitor.LOG_EVENT_ID_AGENT_LOGGING_RESULTS -> "Agent logging results";
            default -> "Unknown log event ID: " + code;
        };
        return id;
    }

    private boolean isOpTypeRestore(Bundle eventBundle) {
        return switch (eventBundle.getInt(
                BackupManagerMonitor.EXTRA_LOG_OPERATION_TYPE, -1)) {
            case BackupAnnotations.OperationType.RESTORE -> true;
            default -> false;
        };
    }
}
