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

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastPrintWriter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;


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
    private static final String INITIAL_SETUP_TIMESTAMP_KEY = "initialSetupTimestamp";
    // Retention period of 60 days (in millisec) for the BMM Events.
    // After tha time has passed the text file containing the BMM events will be emptied
    private static final long LOGS_RETENTION_PERIOD_MILLISEC = 60 * TimeUnit.DAYS.toMillis(1);
    // Size limit for the text file containing the BMM events
    private static final long BMM_FILE_SIZE_LIMIT_BYTES = 25 * 1024 * 1000; // 2.5 MB

    // We cache the value of IsAfterRetentionPeriod() to avoid unnecessary disk I/O
    // mIsAfterRetentionPeriodCached tracks if we have cached the value of IsAfterRetentionPeriod()
    private boolean mIsAfterRetentionPeriodCached = false;
    // The cached value of IsAfterRetentionPeriod()
    private boolean mIsAfterRetentionPeriod;
    // If isFileLargerThanSizeLimit(bmmEvents)  returns true we cache the value to avoid
    // unnecessary disk I/O
   private boolean mIsFileLargerThanSizeLimit = false;

    /**
     * Parses the BackupManagerMonitor bundle for a RESTORE event in a series of strings that
     * will be persisted in a text file and printed in the dumpsys.
     *
     * If the eventBundle passed is not a RESTORE event, return early
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
     * [2023-09-21 14:43:33.824] - Agent logging results
     *   Package: com.android.wallpaperbackup
     *   Agent Logs:
     *           Data Type: wlp_img_system
     *                   Item restored: 0/1
     *                   Agent Error - Category: no_wallpaper, Count: 1
     *           Data Type: wlp_img_lock
     *                   Item restored: 0/1
     *                   Agent Error - Category: no_wallpaper, Count: 1
     */
    public void parseBackupManagerMonitorRestoreEventForDumpsys(Bundle eventBundle) {
        if (isAfterRetentionPeriod()) {
            // We only log data for the first 60 days since setup
            return;
        }

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

        if (bmmEvents.length() == 0) {
            // We are parsing the first restore event.
            // Time to also record the setup timestamp of the device
            recordSetUpTimestamp();
        }

        if(isFileLargerThanSizeLimit(bmmEvents)){
            // Do not write more events if the file is over size limit
            return;
        }

        try (FileOutputStream out = new FileOutputStream(bmmEvents, /*append*/ true);
             PrintWriter pw = new FastPrintWriter(out);) {

            int eventCategory = eventBundle.getInt(BackupManagerMonitor.EXTRA_LOG_EVENT_CATEGORY);
            int eventId = eventBundle.getInt(BackupManagerMonitor.EXTRA_LOG_EVENT_ID);

            if (eventId == BackupManagerMonitor.LOG_EVENT_ID_AGENT_LOGGING_RESULTS &&
                    !hasAgentLogging(eventBundle)) {
                // Do not record an empty agent logging event
                return;
            }

            pw.println("[" + timestamp() + "] - " + getId(eventId));

            if (eventBundle.containsKey(BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME)) {
                pw.println("\tPackage: "
                        + eventBundle.getString(BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME));
            }

            addAgentLogsIfAvailable(eventBundle, pw);
            addExtrasIfAvailable(eventBundle, pw);
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

    /**
     * Extracts some extras (defined in BackupManagerMonitor as EXTRA_LOG_<description>)
     * from the BackupManagerMonitor event. Not all extras have the same importance. For now only
     * focus on extras relating to version mismatches between packages on the source and target.
     *
     * When an event with ID LOG_EVENT_ID_RESTORE_VERSION_HIGHER (trying to restore from higher to
     * lower version of a package) parse:
     * EXTRA_LOG_RESTORE_VERSION [int]: the version of the package on the source
     * EXTRA_LOG_RESTORE_ANYWAY [bool]: if the package allows restore any version
     * EXTRA_LOG_RESTORE_VERSION_TARGET [int]: an extra to record the package version on the target
     *
     * When we are performing a V to U downgrade (event with id V_TO_U_RESTORE_SET_LIST) we record
     * the value of the V to U allowlist and denylist:
     * EXTRA_LOG_V_TO_U_ALLOWLIST[string]
     * EXTRA_LOG_V_TO_U_DENYLIST[string]
     */
    private void addExtrasIfAvailable(Bundle eventBundle, PrintWriter pw) {
        if (eventBundle.getInt(BackupManagerMonitor.EXTRA_LOG_EVENT_ID) ==
                BackupManagerMonitor.LOG_EVENT_ID_RESTORE_VERSION_HIGHER) {
            if (eventBundle.containsKey(BackupManagerMonitor.EXTRA_LOG_RESTORE_ANYWAY)) {
                pw.println("\t\tPackage supports RestoreAnyVersion: "
                        + eventBundle.getBoolean(BackupManagerMonitor.EXTRA_LOG_RESTORE_ANYWAY));
            }
            if (eventBundle.containsKey(BackupManagerMonitor.EXTRA_LOG_RESTORE_VERSION)) {
                pw.println("\t\tPackage version on source: "
                        + eventBundle.getLong(BackupManagerMonitor.EXTRA_LOG_RESTORE_VERSION));
            }
            if (eventBundle.containsKey(
                    BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_LONG_VERSION)) {
                pw.println("\t\tPackage version on target: "
                        + eventBundle.getLong(
                        BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_LONG_VERSION));
            }
        }

        if (eventBundle.getInt(BackupManagerMonitor.EXTRA_LOG_EVENT_ID)
                == BackupManagerMonitor.LOG_EVENT_ID_V_TO_U_RESTORE_SET_LIST) {
            if (eventBundle.containsKey(
                    BackupManagerMonitor.EXTRA_LOG_V_TO_U_DENYLIST)) {
                pw.println("\t\tV to U Denylist : "
                        + eventBundle.getString(
                        BackupManagerMonitor.EXTRA_LOG_V_TO_U_DENYLIST));
            }

            if (eventBundle.containsKey(
                    BackupManagerMonitor.EXTRA_LOG_V_TO_U_ALLOWLIST)) {
                pw.println("\t\tV to U Allowllist : "
                        + eventBundle.getString(
                        BackupManagerMonitor.EXTRA_LOG_V_TO_U_ALLOWLIST));
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

    public boolean isFileLargerThanSizeLimit(File events){
        if (!mIsFileLargerThanSizeLimit) {
            mIsFileLargerThanSizeLimit = events.length() > getBMMEventsFileSizeLimit();
        }
        return mIsFileLargerThanSizeLimit;
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
            case BackupManagerMonitor.LOG_EVENT_ID_START_SYSTEM_RESTORE -> "Start system restore";
            case BackupManagerMonitor.LOG_EVENT_ID_START_RESTORE_AT_INSTALL ->
                    "Start restore at install";
            case BackupManagerMonitor.LOG_EVENT_ID_TRANSPORT_ERROR_DURING_START_RESTORE ->
                    "Transport error during start restore";
            case BackupManagerMonitor.LOG_EVENT_ID_CANNOT_GET_NEXT_PKG_NAME ->
                    "Cannot get next package name";
            case BackupManagerMonitor.LOG_EVENT_ID_UNKNOWN_RESTORE_TYPE -> "Unknown restore type";
            case BackupManagerMonitor.LOG_EVENT_ID_KV_RESTORE -> "KV restore";
            case BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE -> "Full restore";
            case BackupManagerMonitor.LOG_EVENT_ID_NO_NEXT_RESTORE_TARGET ->
                    "No next restore target";
            case BackupManagerMonitor.LOG_EVENT_ID_KV_AGENT_ERROR -> "KV agent error";
            case BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_RESTORE_FINISHED ->
                    "Package restore finished";
            case BackupManagerMonitor.LOG_EVENT_ID_TRANSPORT_ERROR_KV_RESTORE ->
                    "Transport error KV restore";
            case BackupManagerMonitor.LOG_EVENT_ID_NO_FEEDER_THREAD -> "No feeder thread";
            case BackupManagerMonitor.LOG_EVENT_ID_FULL_AGENT_ERROR -> "Full agent error";
            case BackupManagerMonitor.LOG_EVENT_ID_TRANSPORT_ERROR_FULL_RESTORE ->
                    "Transport error full restore";
            case BackupManagerMonitor.LOG_EVENT_ID_RESTORE_COMPLETE -> "Restore complete";
            case BackupManagerMonitor.LOG_EVENT_ID_START_PACKAGE_RESTORE -> "Start package restore";
            case BackupManagerMonitor.LOG_EVENT_ID_AGENT_FAILURE -> "Agent failure";
            case BackupManagerMonitor.LOG_EVENT_ID_V_TO_U_RESTORE_PKG_ELIGIBLE ->
                    "V to U restore pkg eligible";
            case BackupManagerMonitor.LOG_EVENT_ID_V_TO_U_RESTORE_PKG_NOT_ELIGIBLE ->
                    "V to U restore pkg not eligible";
            case BackupManagerMonitor.LOG_EVENT_ID_V_TO_U_RESTORE_SET_LIST ->
                    "V to U restore lists";
            case BackupManagerMonitor.LOG_EVENT_ID_RESTORE_AT_INSTALL_INVOKED ->
                    "Invoked restore at install";
            case BackupManagerMonitor.LOG_EVENT_ID_SKIP_RESTORE_AT_INSTALL ->
                    "Skip restore at install";
            case BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_ACCEPTED_FOR_RESTORE ->
                    "Pkg accepted for restore";
            case BackupManagerMonitor.LOG_EVENT_ID_RESTORE_DATA_DOES_NOT_BELONG_TO_PACKAGE ->
                    "Restore data does not belong to package";
            case BackupManagerMonitor.LOG_EVENT_ID_UNABLE_TO_CREATE_AGENT_FOR_RESTORE ->
                    "Unable to create Agent";
            case BackupManagerMonitor.LOG_EVENT_ID_AGENT_CRASHED_BEFORE_RESTORE_DATA_IS_SENT ->
                    "Agent crashed before restore data is streamed";
            case BackupManagerMonitor.LOG_EVENT_ID_FAILED_TO_SEND_DATA_TO_AGENT_DURING_RESTORE ->
                    "Failed to send data to agent";
            case BackupManagerMonitor.LOG_EVENT_ID_AGENT_FAILURE_DURING_RESTORE ->
                    "Agent failure during restore";
            case BackupManagerMonitor.LOG_EVENT_ID_FAILED_TO_READ_DATA_FROM_TRANSPORT ->
                    "Failed to read data from Transport";
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

    /**
     * Store the timestamp when the device was set up (date when the first BMM event is parsed)
     * in a text file.
     */
    @VisibleForTesting
    void recordSetUpTimestamp() {
        File setupDateFile = getSetUpDateFile();
        // record setup timestamp only once
        if (setupDateFile.length() == 0) {
            try (FileOutputStream out = new FileOutputStream(setupDateFile, /*append*/ true);
                 PrintWriter pw = new FastPrintWriter(out);) {
                long currentDate = System.currentTimeMillis();
                pw.println(currentDate);
            } catch (IOException e) {
                Slog.w(TAG, "An error occurred while recording the setup date: "
                        + e.getMessage());
            }
        }

    }

    @VisibleForTesting
    String getSetUpDate() {
        File fname = getSetUpDateFile();
        try (FileInputStream inputStream = new FileInputStream(fname);
             InputStreamReader reader = new InputStreamReader(inputStream);
             BufferedReader bufferedReader = new BufferedReader(reader);) {
            return bufferedReader.readLine();
        } catch (Exception e) {
            Slog.w(TAG, "An error occurred while reading the date: " + e.getMessage());
            return "Could not retrieve setup date";
        }
    }

    @VisibleForTesting
    static boolean isDateAfterNMillisec(long startTimeStamp, long endTimeStamp, long millisec) {
        if (startTimeStamp > endTimeStamp) {
            // Something has gone wrong, timeStamp1 should always precede timeStamp2.
            // Out of caution return true: we would delete the logs rather than
            // risking them being kept for longer than the retention period
            return true;
        }
        long timeDifferenceMillis = endTimeStamp - startTimeStamp;
        return (timeDifferenceMillis >= millisec);
    }

    /**
     * Check if current date is after retention period
     */
    @VisibleForTesting
    boolean isAfterRetentionPeriod() {
        if (mIsAfterRetentionPeriodCached) {
            return mIsAfterRetentionPeriod;
        } else {
            File setUpDateFile = getSetUpDateFile();
            if (setUpDateFile.length() == 0) {
                // We are yet to record a setup date. This means we haven't parsed the first event.
                mIsAfterRetentionPeriod = false;
                mIsAfterRetentionPeriodCached = true;
                return false;
            }
            try {
                long setupTimestamp = Long.parseLong(getSetUpDate());
                long currentTimestamp = System.currentTimeMillis();
                mIsAfterRetentionPeriod = isDateAfterNMillisec(setupTimestamp, currentTimestamp,
                        getRetentionPeriodInMillisec());
                mIsAfterRetentionPeriodCached = true;
                return mIsAfterRetentionPeriod;
            } catch (NumberFormatException e) {
                // An error occurred when parsing the setup timestamp.
                // Out of caution return true: we would delete the logs rather than
                // risking them being kept for longer than the retention period
                mIsAfterRetentionPeriod = true;
                mIsAfterRetentionPeriodCached = true;
                return true;
            }
        }
    }

    @VisibleForTesting
    File getSetUpDateFile() {
        File dataDir = new File(Environment.getDataDirectory(), BACKUP_PERSISTENT_DIR);
        File setupDateFile = new File(dataDir, INITIAL_SETUP_TIMESTAMP_KEY + ".txt");
        return setupDateFile;
    }

    @VisibleForTesting
    long getRetentionPeriodInMillisec() {
        return LOGS_RETENTION_PERIOD_MILLISEC;
    }

    @VisibleForTesting
    long getBMMEventsFileSizeLimit(){
        return BMM_FILE_SIZE_LIMIT_BYTES;
    }

    /**
     * Delete the BMM Events file after the retention period has passed.
     *
     * @return true if the retention period has passed false otherwise.
     * we want to return true even if we were unable to delete the file, as this will prevent
     * expired BMM events from being printed to the dumpsys
     */
    public boolean deleteExpiredBMMEvents() {
        try {
            if (isAfterRetentionPeriod()) {
                File bmmEvents = getBMMEventsFile();
                if (bmmEvents.exists()) {
                    if (bmmEvents.delete()) {
                        Slog.i(TAG, "Deleted expired BMM Events");
                    } else {
                        Slog.e(TAG, "Unable to delete expired BMM Events");
                    }
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            // Handle any unexpected exceptions
            // To be safe we return true as we want to avoid exposing expired BMMEvents
            return true;
        }
    }
}
