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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.backup.BackupAnnotations.OperationType;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.server.backup.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
@SystemApi
public final class BackupRestoreEventLogger {
    private static final String TAG = "BackupRestoreEventLogger";

    /**
     * Max number of unique data types for which an instance of this logger can store info. Attempts
     * to use more distinct data type values will be rejected.
     *
     * @hide
     */
    public static final int DATA_TYPES_ALLOWED = 150;

    /**
     * Denotes that the annotated element identifies a data type as required by the logging methods
     * of {@code BackupRestoreEventLogger}
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    public @interface BackupRestoreDataType {}

    /**
     * Denotes that the annotated element identifies an error type as required by the logging
     * methods of {@code BackupRestoreEventLogger}
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    public @interface BackupRestoreError {}

    private final int mOperationType;
    private final Map<String, DataTypeResult> mResults = new HashMap<>();
    private final MessageDigest mHashDigest;

    /**
     * @param operationType type of the operation for which logging will be performed. See
     *                      {@link OperationType}. Attempts to use logging methods that don't match
     *                      the specified operation type will be rejected (e.g. use backup methods
     *                      for a restore logger and vice versa).
     *
     * @hide
     */
    public BackupRestoreEventLogger(@OperationType int operationType) {
        mOperationType = operationType;

        MessageDigest hashDigest = null;
        try {
            hashDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            Slog.w("Couldn't create MessageDigest for hash computation", e);
        }
        mHashDigest = hashDigest;
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
     */
    public void logItemsBackedUp(@NonNull @BackupRestoreDataType String dataType, int count) {
        logSuccess(OperationType.BACKUP, dataType, count);
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
     */
    public void logItemsBackupFailed(@NonNull @BackupRestoreDataType String dataType, int count,
            @Nullable @BackupRestoreError String error) {
        logFailure(OperationType.BACKUP, dataType, count, error);
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
     */
    public void logBackupMetadata(@NonNull @BackupRestoreDataType String dataType,
            @NonNull String metaData) {
        logMetaData(OperationType.BACKUP, dataType, metaData);
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
     */
    public void logItemsRestored(@NonNull @BackupRestoreDataType String dataType, int count) {
        logSuccess(OperationType.RESTORE, dataType, count);
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
     */
    public void logItemsRestoreFailed(@NonNull @BackupRestoreDataType String dataType, int count,
            @Nullable @BackupRestoreError String error) {
        logFailure(OperationType.RESTORE, dataType, count, error);
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
     */
    public void logRestoreMetadata(@NonNull @BackupRestoreDataType String dataType,
            @NonNull  String metadata) {
        logMetaData(OperationType.RESTORE, dataType, metadata);
    }

    /**
     * Get the contents of this logger. This method should only be used by B&R code in Android
     * Framework.
     *
     * @hide
     */
    public List<DataTypeResult> getLoggingResults() {
        return new ArrayList<>(mResults.values());
    }

    /**
     * Get the operation type for which this logger was created. This method should only be used
     * by B&R code in Android Framework.
     *
     * @hide
     */
    @OperationType
    public int getOperationType() {
        return mOperationType;
    }

    /**
     * Clears data logged. This method should only be used by B&R code in Android Framework.
     *
     * @hide
     */
    public void clearData() {
        mResults.clear();

    }

    private void logSuccess(@OperationType int operationType,
            @BackupRestoreDataType String dataType, int count) {
        DataTypeResult dataTypeResult = getDataTypeResult(operationType, dataType);
        if (dataTypeResult == null) {
            return;
        }

        dataTypeResult.mSuccessCount += count;
        mResults.put(dataType, dataTypeResult);
    }

    private void logFailure(@OperationType int operationType,
            @NonNull @BackupRestoreDataType String dataType, int count,
            @Nullable @BackupRestoreError String error) {
        DataTypeResult dataTypeResult = getDataTypeResult(operationType, dataType);
        if (dataTypeResult == null) {
            return;
        }

        dataTypeResult.mFailCount += count;
        if (error != null) {
            dataTypeResult.mErrors.merge(error, count, Integer::sum);
        }
    }

    private void logMetaData(@OperationType int operationType,
            @NonNull @BackupRestoreDataType String dataType, @NonNull String metaData) {
        if (mHashDigest == null) {
            return;
        }
        DataTypeResult dataTypeResult = getDataTypeResult(operationType, dataType);
        if (dataTypeResult == null) {
            return;
        }

        dataTypeResult.mMetadataHash = getMetaDataHash(metaData);
    }

    /**
     * Get the result container for the given data type.
     *
     * @return {@code DataTypeResult} object corresponding to the given {@code dataType} or
     *         {@code null} if the logger can't accept logs for the given data type.
     */
    @Nullable
    private DataTypeResult getDataTypeResult(@OperationType int operationType,
            @BackupRestoreDataType String dataType) {
        if (operationType != mOperationType) {
            // Operation type for which we're trying to record logs doesn't match the operation
            // type for which this logger instance was created.
            Slog.d(TAG, "Operation type mismatch: logger created for " + mOperationType
                    + ", trying to log for " + operationType);
            return null;
        }

        if (!mResults.containsKey(dataType)) {
            if (mResults.keySet().size() == getDataTypesAllowed()) {
                // This is a new data type and we're already at capacity.
                Slog.d(TAG, "Logger is full, ignoring new data type");
                return null;
            }

            mResults.put(dataType,  new DataTypeResult(dataType));
        }

        return mResults.get(dataType);
    }

    private byte[] getMetaDataHash(String metaData) {
        return mHashDigest.digest(metaData.getBytes(StandardCharsets.UTF_8));
    }

    private int getDataTypesAllowed(){
        if (Flags.enableIncreaseDatatypesForAgentLogging()) {
            return DATA_TYPES_ALLOWED;
        } else {
            return 15;
        }
    }

    /**
     * Encapsulate logging results for a single data type.
     */
    public static final class DataTypeResult implements Parcelable {
        @BackupRestoreDataType
        private final String mDataType;
        private int mSuccessCount;
        private int mFailCount;
        private final Map<String, Integer> mErrors = new HashMap<>();
        private byte[] mMetadataHash;

        public DataTypeResult(@NonNull String dataType) {
            mDataType = dataType;
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
         * @return number of items of the given data type that have failed to back up or restore.
         */
        public int getFailCount() {
            return mFailCount;
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

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString(mDataType);

            dest.writeInt(mSuccessCount);

            dest.writeInt(mFailCount);

            Bundle errorsBundle = new Bundle();
            for (Map.Entry<String, Integer> e : mErrors.entrySet()) {
                errorsBundle.putInt(e.getKey(), e.getValue());
            }
            dest.writeBundle(errorsBundle);

            dest.writeByteArray(mMetadataHash);
        }

        @NonNull
        public static final Parcelable.Creator<DataTypeResult> CREATOR =
                new Parcelable.Creator<>() {
                    public DataTypeResult createFromParcel(Parcel in) {
                        String dataType = in.readString();

                        int successCount = in.readInt();

                        int failCount = in.readInt();

                        Map<String, Integer> errors = new ArrayMap<>();
                        Bundle errorsBundle = in.readBundle(getClass().getClassLoader());
                        for (String key : errorsBundle.keySet()) {
                            errors.put(key, errorsBundle.getInt(key));
                        }

                        byte[] metadataHash = in.createByteArray();

                        DataTypeResult result = new DataTypeResult(dataType);
                        result.mSuccessCount = successCount;
                        result.mFailCount = failCount;
                        result.mErrors.putAll(errors);
                        result.mMetadataHash = metadataHash;
                        return result;
                    }

                    public DataTypeResult[] newArray(int size) {
                        return new DataTypeResult[size];
                    }
                };
    }
}
