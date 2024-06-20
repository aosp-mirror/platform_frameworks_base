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

package com.android.server.notification;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertFalse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.job.JobServiceEngine;
import android.testing.AndroidTestingRunner;

import androidx.test.rule.ServiceTestRule;

import com.android.server.LocalServices;
import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.lang.reflect.Field;

@RunWith(AndroidTestingRunner.class)
public class NotificationHistoryJobServiceTest extends UiServiceTestCase {
    private NotificationHistoryJobService mJobService;

    @Mock
    private JobParameters mJobParams;

    @Captor
    ArgumentCaptor<JobInfo> mJobInfoCaptor;

    @Mock
    private JobScheduler mMockJobScheduler;

    @Mock
    private NotificationManagerInternal mMockNotificationManagerInternal;

    @Rule
    public final ServiceTestRule mServiceRule = new ServiceTestRule();

    @Before
    public void setUp() throws Exception {
        mJobService = spy(new NotificationHistoryJobService());
        mJobService.attachBaseContext(mContext);
        mJobService.onCreate();
        mJobService.onBind(/* intent= */ null);  // Create JobServiceEngine within JobService.
        doNothing().when(mJobService).jobFinished(any(), eq(false));

        mContext.addMockSystemService(JobScheduler.class, mMockJobScheduler);

        // add NotificationManagerInternal to LocalServices
        LocalServices.removeServiceForTest(NotificationManagerInternal.class);
        LocalServices.addService(NotificationManagerInternal.class,
                mMockNotificationManagerInternal);
    }

    @Test
    public void testScheduleJob() {
        // if asked, the job doesn't currently exist yet
        when(mMockJobScheduler.getPendingJob(anyInt())).thenReturn(null);

        // attempt to schedule the job
        NotificationHistoryJobService.scheduleJob(mContext);
        verify(mMockJobScheduler, times(1)).schedule(mJobInfoCaptor.capture());

        // verify various properties of the job that is passed in to the job scheduler
        JobInfo jobInfo = mJobInfoCaptor.getValue();
        assertEquals(NotificationHistoryJobService.BASE_JOB_ID, jobInfo.getId());
        assertFalse(jobInfo.isPersisted());
        assertTrue(jobInfo.isPeriodic());
    }

    @Test
    public void testOnStartJob() {
        assertTrue(mJobService.onStartJob(mJobParams));

        verify(mMockNotificationManagerInternal, timeout(500).atLeastOnce()).cleanupHistoryFiles();
    }
}