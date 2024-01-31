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

import static android.app.backup.BackupAnnotations.OperationType.BACKUP;
import static android.app.backup.BackupAnnotations.OperationType.RESTORE;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.fail;

import android.app.backup.BackupRestoreEventLogger.DataTypeResult;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.backup.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupRestoreEventLoggerTest {
    private static final int DATA_TYPES_ALLOWED_AFTER_FLAG = 150;

    private static final int DATA_TYPES_ALLOWED_BEFORE_FLAG = 15;

    private static final String DATA_TYPE_1 = "data_type_1";
    private static final String DATA_TYPE_2 = "data_type_2";
    private static final String ERROR_1 = "error_1";
    private static final String ERROR_2 = "error_2";
    private static final String METADATA_1 = "metadata_1";
    private static final String METADATA_2 = "metadata_2";

    private BackupRestoreEventLogger mLogger;
    private MessageDigest mHashDigest;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() throws Exception {
        mHashDigest = MessageDigest.getInstance("SHA-256");
    }

    @Test
    public void testBackupLogger_rejectsRestoreLogs() {
        mLogger = new BackupRestoreEventLogger(BACKUP);

        mLogger.logItemsRestored(DATA_TYPE_1, /* count */ 5);
        mLogger.logItemsRestoreFailed(DATA_TYPE_1, /* count */ 5, ERROR_1);
        mLogger.logRestoreMetadata(DATA_TYPE_1, /* metadata */ "metadata");

        assertThat(getResultForDataTypeIfPresent(mLogger, DATA_TYPE_1)).isEqualTo(Optional.empty());
    }

    @Test
    public void testRestoreLogger_rejectsBackupLogs() {
        mLogger = new BackupRestoreEventLogger(RESTORE);

        mLogger.logItemsBackedUp(DATA_TYPE_1, /* count */ 5);
        mLogger.logItemsBackupFailed(DATA_TYPE_1, /* count */ 5, ERROR_1);
        mLogger.logBackupMetadata(DATA_TYPE_1, /* metadata */ "metadata");

        assertThat(getResultForDataTypeIfPresent(mLogger, DATA_TYPE_1)).isEqualTo(Optional.empty());
    }

    @Test
    public void testBackupLogger_datatypeLimitFlagOff_onlyAcceptsAllowedNumberOfDataTypes() {
        mSetFlagsRule.disableFlags(Flags.FLAG_ENABLE_INCREASE_DATATYPES_FOR_AGENT_LOGGING);
        mLogger = new BackupRestoreEventLogger(BACKUP);

        for (int i = 0; i < DATA_TYPES_ALLOWED_BEFORE_FLAG; i++) {
            String dataType = DATA_TYPE_1 + i;
            mLogger.logItemsBackedUp(dataType, /* count */ 5);
            mLogger.logItemsBackupFailed(dataType, /* count */ 5, /* error */ null);
            mLogger.logBackupMetadata(dataType, METADATA_1);

            assertThat(getResultForDataTypeIfPresent(mLogger, dataType)).isNotEqualTo(
                    Optional.empty());
        }

        mLogger.logItemsBackedUp(DATA_TYPE_2, /* count */ 5);
        mLogger.logItemsBackupFailed(DATA_TYPE_2, /* count */ 5, /* error */ null);
        mLogger.logRestoreMetadata(DATA_TYPE_2, METADATA_1);
        assertThat(getResultForDataTypeIfPresent(mLogger, DATA_TYPE_2)).isEqualTo(Optional.empty());
    }

    @Test
    public void testRestoreLogger_datatypeLimitFlagOff_onlyAcceptsAllowedNumberOfDataTypes() {
        mSetFlagsRule.disableFlags(Flags.FLAG_ENABLE_INCREASE_DATATYPES_FOR_AGENT_LOGGING);
        mLogger = new BackupRestoreEventLogger(RESTORE);

        for (int i = 0; i < DATA_TYPES_ALLOWED_BEFORE_FLAG; i++) {
            String dataType = DATA_TYPE_1 + i;
            mLogger.logItemsRestored(dataType, /* count */ 5);
            mLogger.logItemsRestoreFailed(dataType, /* count */ 5, /* error */ null);
            mLogger.logRestoreMetadata(dataType, METADATA_1);

            assertThat(getResultForDataTypeIfPresent(mLogger, dataType)).isNotEqualTo(
                    Optional.empty());
        }

        mLogger.logItemsRestored(DATA_TYPE_2, /* count */ 5);
        mLogger.logItemsRestoreFailed(DATA_TYPE_2, /* count */ 5, /* error */ null);
        mLogger.logRestoreMetadata(DATA_TYPE_2, METADATA_1);
        assertThat(getResultForDataTypeIfPresent(mLogger, DATA_TYPE_2)).isEqualTo(Optional.empty());
    }

    @Test
    public void testBackupLogger_datatypeLimitFlagOn_onlyAcceptsAllowedNumberOfDataTypes() {
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_INCREASE_DATATYPES_FOR_AGENT_LOGGING);
        mLogger = new BackupRestoreEventLogger(BACKUP);

        for (int i = 0; i < DATA_TYPES_ALLOWED_AFTER_FLAG; i++) {
            String dataType = DATA_TYPE_1 + i;
            mLogger.logItemsBackedUp(dataType, /* count */ 5);
            mLogger.logItemsBackupFailed(dataType, /* count */ 5, /* error */ null);
            mLogger.logBackupMetadata(dataType, METADATA_1);

            assertThat(getResultForDataTypeIfPresent(mLogger, dataType)).isNotEqualTo(
                    Optional.empty());
        }

        mLogger.logItemsBackedUp(DATA_TYPE_2, /* count */ 5);
        mLogger.logItemsBackupFailed(DATA_TYPE_2, /* count */ 5, /* error */ null);
        mLogger.logRestoreMetadata(DATA_TYPE_2, METADATA_1);
        assertThat(getResultForDataTypeIfPresent(mLogger, DATA_TYPE_2)).isEqualTo(Optional.empty());
    }

    @Test
    public void testRestoreLogger_datatypeLimitFlagOn_onlyAcceptsAllowedNumberOfDataTypes() {
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_INCREASE_DATATYPES_FOR_AGENT_LOGGING);
        mLogger = new BackupRestoreEventLogger(RESTORE);

        for (int i = 0; i < DATA_TYPES_ALLOWED_AFTER_FLAG; i++) {
            String dataType = DATA_TYPE_1 + i;
            mLogger.logItemsRestored(dataType, /* count */ 5);
            mLogger.logItemsRestoreFailed(dataType, /* count */ 5, /* error */ null);
            mLogger.logRestoreMetadata(dataType, METADATA_1);

            assertThat(getResultForDataTypeIfPresent(mLogger, dataType)).isNotEqualTo(
                    Optional.empty());
        }

        mLogger.logItemsRestored(DATA_TYPE_2, /* count */ 5);
        mLogger.logItemsRestoreFailed(DATA_TYPE_2, /* count */ 5, /* error */ null);
        mLogger.logRestoreMetadata(DATA_TYPE_2, METADATA_1);
        assertThat(getResultForDataTypeIfPresent(mLogger, DATA_TYPE_2)).isEqualTo(Optional.empty());
    }

    @Test
    public void testLogBackupMetadata_repeatedCalls_recordsLatestMetadataHash() {
        mLogger = new BackupRestoreEventLogger(BACKUP);

        mLogger.logBackupMetadata(DATA_TYPE_1, METADATA_1);
        mLogger.logBackupMetadata(DATA_TYPE_1, METADATA_2);

        byte[] recordedHash = getResultForDataType(mLogger, DATA_TYPE_1).getMetadataHash();
        byte[] expectedHash = getMetaDataHash(METADATA_2);
        assertThat(Arrays.equals(recordedHash, expectedHash)).isTrue();
    }

    @Test
    public void testLogRestoreMetadata_repeatedCalls_recordsLatestMetadataHash() {
        mLogger = new BackupRestoreEventLogger(RESTORE);

        mLogger.logRestoreMetadata(DATA_TYPE_1, METADATA_1);
        mLogger.logRestoreMetadata(DATA_TYPE_1, METADATA_2);

        byte[] recordedHash = getResultForDataType(mLogger, DATA_TYPE_1).getMetadataHash();
        byte[] expectedHash = getMetaDataHash(METADATA_2);
        assertThat(Arrays.equals(recordedHash, expectedHash)).isTrue();
    }

    @Test
    public void testLogItemsBackedUp_repeatedCalls_recordsTotalItems() {
        mLogger = new BackupRestoreEventLogger(BACKUP);

        int firstCount = 10;
        int secondCount = 5;
        mLogger.logItemsBackedUp(DATA_TYPE_1, firstCount);
        mLogger.logItemsBackedUp(DATA_TYPE_1, secondCount);

        int dataTypeCount = getResultForDataType(mLogger, DATA_TYPE_1).getSuccessCount();
        assertThat(dataTypeCount).isEqualTo(firstCount + secondCount);
    }

    @Test
    public void testLogItemsRestored_repeatedCalls_recordsTotalItems() {
        mLogger = new BackupRestoreEventLogger(RESTORE);

        int firstCount = 10;
        int secondCount = 5;
        mLogger.logItemsRestored(DATA_TYPE_1, firstCount);
        mLogger.logItemsRestored(DATA_TYPE_1, secondCount);

        int dataTypeCount = getResultForDataType(mLogger, DATA_TYPE_1).getSuccessCount();
        assertThat(dataTypeCount).isEqualTo(firstCount + secondCount);
    }

    @Test
    public void testLogItemsBackedUp_multipleDataTypes_recordsEachDataType() {
        mLogger = new BackupRestoreEventLogger(BACKUP);

        int firstCount = 10;
        int secondCount = 5;
        mLogger.logItemsBackedUp(DATA_TYPE_1, firstCount);
        mLogger.logItemsBackedUp(DATA_TYPE_2, secondCount);

        int firstDataTypeCount = getResultForDataType(mLogger, DATA_TYPE_1).getSuccessCount();
        int secondDataTypeCount = getResultForDataType(mLogger, DATA_TYPE_2).getSuccessCount();
        assertThat(firstDataTypeCount).isEqualTo(firstCount);
        assertThat(secondDataTypeCount).isEqualTo(secondCount);
    }

    @Test
    public void testLogItemsRestored_multipleDataTypes_recordsEachDataType() {
        mLogger = new BackupRestoreEventLogger(RESTORE);

        int firstCount = 10;
        int secondCount = 5;
        mLogger.logItemsRestored(DATA_TYPE_1, firstCount);
        mLogger.logItemsRestored(DATA_TYPE_2, secondCount);

        int firstDataTypeCount = getResultForDataType(mLogger, DATA_TYPE_1).getSuccessCount();
        int secondDataTypeCount = getResultForDataType(mLogger, DATA_TYPE_2).getSuccessCount();
        assertThat(firstDataTypeCount).isEqualTo(firstCount);
        assertThat(secondDataTypeCount).isEqualTo(secondCount);
    }

    @Test
    public void testLogItemsBackupFailed_repeatedCalls_recordsTotalItems() {
        mLogger = new BackupRestoreEventLogger(BACKUP);

        int firstCount = 10;
        int secondCount = 5;
        mLogger.logItemsBackupFailed(DATA_TYPE_1, firstCount, /* error */ null);
        mLogger.logItemsBackupFailed(DATA_TYPE_1, secondCount, "error");

        int dataTypeCount = getResultForDataType(mLogger, DATA_TYPE_1).getFailCount();
        assertThat(dataTypeCount).isEqualTo(firstCount + secondCount);
    }

    @Test
    public void testLogItemsRestoreFailed_repeatedCalls_recordsTotalItems() {
        mLogger = new BackupRestoreEventLogger(RESTORE);

        int firstCount = 10;
        int secondCount = 5;
        mLogger.logItemsRestoreFailed(DATA_TYPE_1, firstCount, /* error */ null);
        mLogger.logItemsRestoreFailed(DATA_TYPE_1, secondCount, "error");

        int dataTypeCount = getResultForDataType(mLogger, DATA_TYPE_1).getFailCount();
        assertThat(dataTypeCount).isEqualTo(firstCount + secondCount);
    }

    @Test
    public void testLogItemsBackupFailed_multipleErrors_recordsEachError() {
        mLogger = new BackupRestoreEventLogger(BACKUP);

        int firstCount = 10;
        int secondCount = 5;
        mLogger.logItemsBackupFailed(DATA_TYPE_1, firstCount, ERROR_1);
        mLogger.logItemsBackupFailed(DATA_TYPE_1, secondCount, ERROR_2);

        int firstErrorTypeCount =
                getResultForDataType(mLogger, DATA_TYPE_1).getErrors().get(ERROR_1);
        int secondErrorTypeCount =
                getResultForDataType(mLogger, DATA_TYPE_1).getErrors().get(ERROR_2);
        assertThat(firstErrorTypeCount).isEqualTo(firstCount);
        assertThat(secondErrorTypeCount).isEqualTo(secondCount);
    }

    @Test
    public void testLogItemsRestoreFailed_multipleErrors_recordsEachError() {
        mLogger = new BackupRestoreEventLogger(RESTORE);

        int firstCount = 10;
        int secondCount = 5;
        mLogger.logItemsRestoreFailed(DATA_TYPE_1, firstCount, ERROR_1);
        mLogger.logItemsRestoreFailed(DATA_TYPE_1, secondCount, ERROR_2);

        int firstErrorTypeCount =
                getResultForDataType(mLogger, DATA_TYPE_1).getErrors().get(ERROR_1);
        int secondErrorTypeCount =
                getResultForDataType(mLogger, DATA_TYPE_1).getErrors().get(ERROR_2);
        assertThat(firstErrorTypeCount).isEqualTo(firstCount);
        assertThat(secondErrorTypeCount).isEqualTo(secondCount);
    }

    @Test
    public void testGetLoggingResults_resultsParceledAndUnparceled_recreatedCorrectly() {
        mLogger = new BackupRestoreEventLogger(RESTORE);
        int firstTypeSuccessCount = 1;
        int firstTypeErrorOneCount = 2;
        int firstTypeErrorTwoCount = 3;
        mLogger.logItemsRestored(DATA_TYPE_1, firstTypeSuccessCount);
        mLogger.logItemsRestoreFailed(DATA_TYPE_1, firstTypeErrorOneCount, ERROR_1);
        mLogger.logItemsRestoreFailed(DATA_TYPE_1, firstTypeErrorTwoCount, ERROR_2);
        mLogger.logRestoreMetadata(DATA_TYPE_1, METADATA_1);
        int secondTypeSuccessCount = 4;
        int secondTypeErrorOneCount = 5;
        mLogger.logItemsRestored(DATA_TYPE_2, secondTypeSuccessCount);
        mLogger.logItemsRestoreFailed(DATA_TYPE_2, secondTypeErrorOneCount, ERROR_1);

        List<DataTypeResult> resultsList = mLogger.getLoggingResults();
        Parcel parcel = Parcel.obtain();

        parcel.writeParcelableList(resultsList, /* flags= */ 0);

        parcel.setDataPosition(0);
        List<DataTypeResult> recreatedList = new ArrayList<>();
        parcel.readParcelableList(
                recreatedList, DataTypeResult.class.getClassLoader(), DataTypeResult.class);

        assertThat(recreatedList.get(0).getDataType()).isEqualTo(DATA_TYPE_1);
        assertThat(recreatedList.get(0).getSuccessCount()).isEqualTo(firstTypeSuccessCount);
        assertThat(recreatedList.get(0).getFailCount())
                .isEqualTo(firstTypeErrorOneCount + firstTypeErrorTwoCount);
        assertThat(recreatedList.get(0).getErrors().get(ERROR_1)).isEqualTo(firstTypeErrorOneCount);
        assertThat(recreatedList.get(0).getErrors().get(ERROR_2)).isEqualTo(firstTypeErrorTwoCount);
        assertThat(recreatedList.get(1).getDataType()).isEqualTo(DATA_TYPE_2);
        assertThat(recreatedList.get(1).getSuccessCount()).isEqualTo(secondTypeSuccessCount);
        assertThat(recreatedList.get(1).getFailCount()).isEqualTo(secondTypeErrorOneCount);
        assertThat(recreatedList.get(1).getErrors().get(ERROR_1))
                .isEqualTo(secondTypeErrorOneCount);
    }

    @Test
    public void testClearData_clearsAllResults() {
        mLogger = new BackupRestoreEventLogger(BACKUP);
        mLogger.logItemsBackedUp(DATA_TYPE_1, 5);
        mLogger.logItemsBackedUp(DATA_TYPE_2, 4);
        mLogger.logItemsBackupFailed(DATA_TYPE_2, 1, ERROR_1);
        assertThat(mLogger.getLoggingResults()).isNotEmpty();

        mLogger.clearData();

        assertThat(mLogger.getLoggingResults()).isEmpty();
    }

    private static DataTypeResult getResultForDataType(
            BackupRestoreEventLogger logger, String dataType) {
        Optional<DataTypeResult> result = getResultForDataTypeIfPresent(logger, dataType);
        if (result.isEmpty()) {
            fail("Failed to find result for data type: " + dataType);
        }
        return result.get();
    }

    private static Optional<DataTypeResult> getResultForDataTypeIfPresent(
            BackupRestoreEventLogger logger, String dataType) {
        List<DataTypeResult> resultList = logger.getLoggingResults();
        return resultList.stream()
                .filter(dataTypeResult -> dataTypeResult.getDataType().equals(dataType))
                .findAny();
    }

    private byte[] getMetaDataHash(String metaData) {
        return mHashDigest.digest(metaData.getBytes(StandardCharsets.UTF_8));
    }
}
