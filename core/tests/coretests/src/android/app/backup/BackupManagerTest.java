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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.backup.BackupAnnotations.BackupDestination;
import android.app.backup.BackupAnnotations.OperationType;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupManagerTest {
    private BackupManager mBackupManager;

    private static final int USER_ID = 12;

    @Mock
    Context mContext;
    @Mock
    IBackupManager mIBackupManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mBackupManager = new BackupManager(mContext);
        BackupManager.sService = mIBackupManager;
    }

    @Test
    public void testSetFrameworkSchedulingEnabled_delegatesToService() throws RemoteException {
        when(mContext.getUserId()).thenReturn(USER_ID);
        mBackupManager.setFrameworkSchedulingEnabled(true);

        verify(mIBackupManager).setFrameworkSchedulingEnabledForUser(
                USER_ID, /* isEnabled= */true);
    }

    @Test
    public void testGetBackupRestoreEventLogger_returnsBackupLoggerForBackup() {
        BackupAgent agent = getTestAgent();
        agent.onCreate(UserHandle.SYSTEM, BackupDestination.CLOUD,
                OperationType.BACKUP);

        BackupRestoreEventLogger logger = mBackupManager.getBackupRestoreEventLogger(agent);

        assertThat(logger.getOperationType()).isEqualTo(OperationType.BACKUP);
    }

    @Test
    public void testGetBackupRestoreEventLogger_returnsRestoreLoggerForRestore() {
        BackupAgent agent = getTestAgent();
        agent.onCreate(UserHandle.SYSTEM, BackupDestination.CLOUD,
                OperationType.RESTORE);

        BackupRestoreEventLogger logger = mBackupManager.getBackupRestoreEventLogger(agent);

        assertThat(logger.getOperationType()).isEqualTo(OperationType.RESTORE);
    }

    @Test
    public void testGetBackupRestoreEventLogger_uninitialisedAgent_throwsException() {
        BackupAgent agent = getTestAgent();

        assertThrows(IllegalStateException.class,
                () -> mBackupManager.getBackupRestoreEventLogger(agent));
    }

    @Test
    public void testGetDelayedRestoreLogger_returnsRestoreLogger() {
        BackupRestoreEventLogger logger = mBackupManager.getDelayedRestoreLogger();

        assertThat(logger.getOperationType()).isEqualTo(OperationType.RESTORE);
    }

    private static BackupAgent getTestAgent() {
        return new BackupAgent() {
            @Override
            public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
                    ParcelFileDescriptor newState) throws IOException {

            }

            @Override
            public void onRestore(BackupDataInput data, int appVersionCode,
                    ParcelFileDescriptor newState) throws IOException {

            }
        };
    }

}
