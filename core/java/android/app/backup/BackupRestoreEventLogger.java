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

package android.app.backup;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.List;
import java.util.Map;

// TODO(b/244436184): Make this @SystemApi
/**
 * Class to log B&R stats for each data type that is backed up and restored by the calling app.
 *
 * The logger instance is designed to accept a limited number of unique
 * {link @BackupRestoreDataType} values, as determined by the underlying implementation. Apps are
 * expected to have a small pre-defined set of data type values they use. Attempts to log too many
 * unique values will be rejected.
 *
 * @hide
 */
public class BackupRestoreEventLogger {
    /**
     * Max number of unique data types for which an instance of this logger can store info. Attempts
     * to use more distinct data type values will be rejected.
     */
    public static final int DATA_TYPES_ALLOWED = 15;

    /**
     * Operation types for which this logger can be used.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            OperationType.BACKUP,
            OperationType.RESTORE
    })
    @interface OperationType {
        int BACKUP = 1;
        int RESTORE = 2;
    }

    /**
     * Denotes that the annotated element identifies a data type as required by the logging methods
     * of {@code BackupRestoreEventLogger}
     */
    @Retention(RetentionPolicy.SOURCE)
    public @interface BackupRestoreDataType {}

    /**
     * Denotes that the annotated element identifies an error type as required by the logging
     * methods of {@code BackupRestoreEventLogger}
     */
    @Retention(RetentionPolicy.SOURCE)
    public @interface BackupRestoreError {}

    private final int mOperationType;

    /**
     * @param operationType type of the operation for which logging will be performed. See
     *                      {@link OperationType}. Attempts to use logging methods that don't match
     *                      the specified operation type will be rejected (e.g. use backup methods
     *                      for a restore logger and vice versa).
     */
    public BackupRestoreEventLogger(@OperationType int operationType) {
        mOperationType = operationType;
    }

    /**
     * Report progress during a backup operation. Call this method for each distinct data type that
     * your {@code BackupAgent} implementation handles for any items of that type that have been
     * successfully backed up. Repeated calls to this method with the same {@code dataType} will
     * increase the total count of items associated with this data type by {@code count}.
     *
     * This method should be called from a {@link BackupAgent} implementation during an ongoing
     * backup operation.
     *
     * @param dataType the type of data being backed.
     * @param count number of items of the given type that have been successfully backed up.
     *
     * @return boolean, indicating whether the log has been accepted.
     */
    public boolean logItemsBackedUp(@NonNull @BackupRestoreDataType String dataType, int count) {
        return true;
    }

    /**
     * Report errors during a backup operation. Call this method whenever items of a certain data
     * type failed to back up. Repeated calls to this method with the same {@code dataType} /
     * {@code error} will increase the total count of items associated with this data type / error
     * by {@code count}.
     *
     * This method should be called from a {@link BackupAgent} implementation during an ongoing
     * backup operation.
     *
     * @param dataType the type of data being backed.
     * @param count number of items of the given type that have failed to back up.
     * @param error optional, the error that has caused the failure.
     *
     * @return boolean, indicating whether the log has been accepted.
     */
    public boolean logItemsBackupFailed(@NonNull @BackupRestoreDataType String dataType, int count,
            @Nullable @BackupRestoreError String error) {
        return true;
    }

    /**
     * Report metadata associated with a data type that is currently being backed up, e.g. name of
     * the selected wallpaper file / package. Repeated calls to this method with the same {@code
     * dataType} will overwrite the previously supplied {@code metaData} value.
     *
     * The logger does not store or transmit the provided metadata value. Instead, it’s replaced
     * with the SHA-256 hash of the provided string.
     *
     * This method should be called from a {@link BackupAgent} implementation during an ongoing
     * backup operation.
     *
     * @param dataType the type of data being backed up.
     * @param metaData the metadata associated with the data type.
     *
     * @return boolean, indicating whether the log has been accepted.
     */
    public boolean logBackupMetaData(@NonNull @BackupRestoreDataType String dataType,
            @NonNull String metaData) {
        return true;
    }

    /**
     * Report progress during a restore operation. Call this method for each distinct data type that
     * your {@code BackupAgent} implementation handles if any items of that type have been
     * successfully restored. Repeated calls to this method with the same {@code dataType} will
     * increase the total count of items associated with this data type by {@code count}.
     *
     * This method should either be called from a {@link BackupAgent} implementation during an
     * ongoing restore operation or during any delayed restore actions the package had scheduled
     * earlier (e.g. complete the restore once a certain dependency becomes available on the
     * device).
     *
     * @param dataType the type of data being restored.
     * @param count number of items of the given type that have been successfully restored.
     *
     * @return boolean, indicating whether the log has been accepted.
     */
    public boolean logItemsRestored(@NonNull @BackupRestoreDataType String dataType, int count) {
        return true;
    }

    /**
     * Report errors during a restore operation. Call this method whenever items of a certain data
     * type failed to restore. Repeated calls to this method with the same {@code dataType} /
     * {@code error} will increase the total count of items associated with this data type / error
     * by {@code count}.
     *
     * This method should either be called from a {@link BackupAgent} implementation during an
     * ongoing restore operation or during any delayed restore actions the package had scheduled
     * earlier (e.g. complete the restore once a certain dependency becomes available on the
     * device).
     *
     * @param dataType the type of data being restored.
     * @param count number of items of the given type that have failed to restore.
     * @param error optional, the error that has caused the failure.
     *
     * @return boolean, indicating whether the log has been accepted.
     */
    public boolean logItemsRestoreFailed(@NonNull @BackupRestoreDataType String dataType, int count,
            @Nullable @BackupRestoreError String error) {
        return true;
    }

    /**
     * Report metadata associated with a data type that is currently being restored, e.g. name of
     * the selected wallpaper file / package. Repeated calls to this method with the same
     * {@code dataType} will overwrite the previously supplied {@code metaData} value.
     *
     * The logger does not store or transmit the provided metadata value. Instead, it’s replaced
     * with the SHA-256 hash of the provided string.
     *
     * This method should either be called from a {@link BackupAgent} implementation during an
     * ongoing restore operation or during any delayed restore actions the package had scheduled
     * earlier (e.g. complete the restore once a certain dependency becomes available on the
     * device).
     *
     * @param dataType the type of data being restored.
     * @param metadata the metadata associated with the data type.
     *
     * @return boolean, indicating whether the log has been accepted.
     */
    public boolean logRestoreMetadata(@NonNull @BackupRestoreDataType String dataType,
            @NonNull  String metadata) {
        return true;
    }

    /**
     * Get the contents of this logger. This method should only be used by B&R code in Android
     * Framework.
     *
     * @hide
     */
    public List<DataTypeResult> getLoggingResults() {
        return Collections.emptyList();
    }

    /**
     * Get the operation type for which this logger was created. This method should only be used
     * by B&R code in Android Framework.
     *
     * @hide
     */
    public @OperationType int getOperationType() {
        return mOperationType;
    }

    /**
     * Encapsulate logging results for a single data type.
     */
    public static class DataTypeResult {
        @BackupRestoreDataType
        private final String mDataType;
        private final int mSuccessCount;
        private final Map<String, Integer> mErrors;
        private final byte[] mMetadataHash;

        public DataTypeResult(String dataType, int successCount,
                Map<String, Integer> errors, byte[] metadataHash) {
            mDataType = dataType;
            mSuccessCount = successCount;
            mErrors = errors;
            mMetadataHash = metadataHash;
        }

        @NonNull
        @BackupRestoreDataType
        public String getDataType() {
            return mDataType;
        }

        /**
         * @return number of items of the given data type that have been successfully backed up or
         *         restored.
         */
        public int getSuccessCount() {
            return mSuccessCount;
        }

        /**
         * @return mapping of {@link BackupRestoreError} to the count of items that are affected by
         *         the error.
         */
        @NonNull
        public Map<String, Integer> getErrors() {
            return mErrors;
        }

        /**
         * @return SHA-256 hash of the metadata or {@code null} of no metadata has been logged for
         *         this data type.
         */
        @Nullable
        public byte[] getMetadataHash() {
            return mMetadataHash;
        }
    }
}
