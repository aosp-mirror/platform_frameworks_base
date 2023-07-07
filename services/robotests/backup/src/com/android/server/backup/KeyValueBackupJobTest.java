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

package com.android.server.backup;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.content.Context;
import android.os.Handler;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import com.android.server.testing.shadows.ShadowSystemServiceRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowSystemServiceRegistry.class})
@Presubmit
public class KeyValueBackupJobTest {
    private Context mContext;
    private BackupManagerConstants mConstants;

    @Mock
    private UserBackupManagerService mUserBackupManagerService;

    @UserIdInt private int mUserOneId;
    @UserIdInt private int mUserTwoId;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mConstants = new BackupManagerConstants(Handler.getMain(), mContext.getContentResolver());
        mConstants.start();
        when(mUserBackupManagerService.getConstants()).thenReturn(mConstants);
        when(mUserBackupManagerService.isFrameworkSchedulingEnabled()).thenReturn(true);

        mUserOneId = UserHandle.USER_SYSTEM;
        mUserTwoId = mUserOneId + 1;
    }

    @After
    public void tearDown() throws Exception {
        mConstants.stop();
        KeyValueBackupJob.cancel(mUserOneId, mContext);
        KeyValueBackupJob.cancel(mUserTwoId, mContext);
    }

    @Test
    public void testSchedule_isNoopIfDisabled() {
        when(mUserBackupManagerService.isFrameworkSchedulingEnabled()).thenReturn(false);
        KeyValueBackupJob.schedule(mUserOneId, mContext, mUserBackupManagerService);

        assertThat(KeyValueBackupJob.isScheduled(mUserOneId)).isFalse();
    }

    @Test
    public void testSchedule_schedulesJobIfEnabled() {
        when(mUserBackupManagerService.isFrameworkSchedulingEnabled()).thenReturn(true);
        KeyValueBackupJob.schedule(mUserOneId, mContext, mUserBackupManagerService);

        assertThat(KeyValueBackupJob.isScheduled(mUserOneId)).isTrue();
    }

    @Test
    public void testIsScheduled_beforeScheduling_returnsFalse() {
        assertThat(KeyValueBackupJob.isScheduled(mUserOneId)).isFalse();
        assertThat(KeyValueBackupJob.isScheduled(mUserTwoId)).isFalse();
    }

    @Test
    public void testIsScheduled_afterScheduling_returnsTrue() {
        KeyValueBackupJob.schedule(mUserOneId, mContext, mUserBackupManagerService);
        KeyValueBackupJob.schedule(mUserTwoId, mContext, mUserBackupManagerService);

        assertThat(KeyValueBackupJob.isScheduled(mUserOneId)).isTrue();
        assertThat(KeyValueBackupJob.isScheduled(mUserTwoId)).isTrue();
    }

    @Test
    public void testIsScheduled_afterCancelling_returnsFalse() {
        KeyValueBackupJob.schedule(mUserOneId, mContext, mUserBackupManagerService);
        KeyValueBackupJob.schedule(mUserTwoId, mContext, mUserBackupManagerService);
        KeyValueBackupJob.cancel(mUserOneId, mContext);
        KeyValueBackupJob.cancel(mUserTwoId, mContext);

        assertThat(KeyValueBackupJob.isScheduled(mUserOneId)).isFalse();
        assertThat(KeyValueBackupJob.isScheduled(mUserTwoId)).isFalse();
    }

    @Test
    public void testIsScheduled_afterScheduling_returnsTrueOnlyForScheduledUser() {
        KeyValueBackupJob.schedule(mUserOneId, mContext, mUserBackupManagerService);

        assertThat(KeyValueBackupJob.isScheduled(mUserOneId)).isTrue();
        assertThat(KeyValueBackupJob.isScheduled(mUserTwoId)).isFalse();
    }

    @Test
    public void testIsScheduled_afterCancelling_returnsFalseOnlyForCancelledUser() {
        KeyValueBackupJob.schedule(mUserOneId, mContext, mUserBackupManagerService);
        KeyValueBackupJob.schedule(mUserTwoId, mContext, mUserBackupManagerService);
        KeyValueBackupJob.cancel(mUserOneId, mContext);

        assertThat(KeyValueBackupJob.isScheduled(mUserOneId)).isFalse();
        assertThat(KeyValueBackupJob.isScheduled(mUserTwoId)).isTrue();
    }
}
