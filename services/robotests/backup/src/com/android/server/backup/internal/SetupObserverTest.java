/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.internal;

import static com.google.common.truth.Truth.assertThat;

import android.app.job.JobScheduler;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import com.android.server.backup.FullBackupJob;
import com.android.server.backup.JobIdManager;
import com.android.server.backup.KeyValueBackupJob;
import com.android.server.backup.TransportManager;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.testing.BackupManagerServiceTestUtils;
import com.android.server.testing.shadows.ShadowApplicationPackageManager;
import com.android.server.testing.shadows.ShadowSystemServiceRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowJobScheduler;

import java.io.File;

/**
 * Tests verifying the interaction between {@link SetupObserver} and {@link
 * UserBackupManagerService}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(
        shadows = {
            ShadowApplicationPackageManager.class,
            ShadowJobScheduler.class,
            ShadowSystemServiceRegistry.class
        })
@Presubmit
public class SetupObserverTest {
    private static final String TAG = "SetupObserverTest";
    private static final int USER_ID = 10;

    @Mock private TransportManager mTransportManager;

    private Context mContext;
    private UserBackupManagerService mUserBackupManagerService;
    private HandlerThread mHandlerThread;
    private ShadowJobScheduler mShadowJobScheduler;

    /** Setup state. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mHandlerThread = BackupManagerServiceTestUtils.startSilentBackupThread(TAG);
        mUserBackupManagerService =
                BackupManagerServiceTestUtils.createUserBackupManagerServiceAndRunTasks(
                        USER_ID,
                        mContext,
                        mHandlerThread,
                        new File(mContext.getDataDir(), "test1"),
                        new File(mContext.getDataDir(), "test2"),
                        mTransportManager);
        mShadowJobScheduler = Shadows.shadowOf(mContext.getSystemService(JobScheduler.class));
    }

    /** Test observer handles changes from not setup -> setup correctly. */
    @Test
    public void testOnChange_whenNewlySetup_updatesState() throws Exception {
        SetupObserver setupObserver = new SetupObserver(mUserBackupManagerService, new Handler());
        mUserBackupManagerService.setSetupComplete(false);
        changeSetupCompleteSettingForUser(true, USER_ID);

        setupObserver.onChange(true);

        assertThat(mUserBackupManagerService.isSetupComplete()).isTrue();
    }

    /** Test observer handles changes from setup -> not setup correctly. */
    @Test
    public void testOnChange_whenPreviouslySetup_doesNotUpdateState() throws Exception {
        SetupObserver setupObserver = new SetupObserver(mUserBackupManagerService, new Handler());
        mUserBackupManagerService.setSetupComplete(true);
        changeSetupCompleteSettingForUser(false, USER_ID);

        setupObserver.onChange(true);

        assertThat(mUserBackupManagerService.isSetupComplete()).isTrue();
    }

    /** Test observer handles changes from not setup -> not setup correctly. */
    @Test
    public void testOnChange_whenNotPreviouslySetup_doesNotUpdateStateIfNoChange()
            throws Exception {
        SetupObserver setupObserver = new SetupObserver(mUserBackupManagerService, new Handler());
        mUserBackupManagerService.setSetupComplete(false);
        changeSetupCompleteSettingForUser(false, USER_ID);

        setupObserver.onChange(true);

        assertThat(mUserBackupManagerService.isSetupComplete()).isFalse();
    }

    /** Test observer handles changes from not setup -> setup correctly. */
    @Test
    public void testOnChange_whenNewlySetup_schedulesBackup() throws Exception {
        SetupObserver setupObserver = new SetupObserver(mUserBackupManagerService, new Handler());
        mUserBackupManagerService.setSetupComplete(false);
        changeSetupCompleteSettingForUser(true, USER_ID);
        // Setup conditions for a full backup job to be scheduled.
        mUserBackupManagerService.setEnabled(true);
        mUserBackupManagerService.enqueueFullBackup("testPackage", /* lastBackedUp */ 0);

        setupObserver.onChange(true);

        assertThat(
                        mShadowJobScheduler.getPendingJob(
                                getJobIdForUser(
                                        KeyValueBackupJob.MIN_JOB_ID,
                                        KeyValueBackupJob.MAX_JOB_ID,
                                        USER_ID)))
                .isNotNull();
        assertThat(
                        mShadowJobScheduler.getPendingJob(
                                getJobIdForUser(
                                        FullBackupJob.MIN_JOB_ID,
                                        FullBackupJob.MAX_JOB_ID,
                                        USER_ID)))
                .isNotNull();
    }

    private int getJobIdForUser(int min, int max, int userId) {
        return JobIdManager.getJobIdForUserId(min, max, userId);
    }

    private void changeSetupCompleteSettingForUser(boolean value, int userId) {
        Settings.Secure.putIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE,
                value ? 1 : 0,
                userId);
    }
}
