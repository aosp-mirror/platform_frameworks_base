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
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
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

@RunWith(AndroidTestingRunner.class)
public class ReviewNotificationPermissionsJobServiceTest extends UiServiceTestCase {
    private ReviewNotificationPermissionsJobService mJobService;
    private JobParameters mJobParams = new JobParameters(null,
            ReviewNotificationPermissionsJobService.JOB_ID, null, null, null,
            0, false, false, null, null, null);

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
        mJobService = new ReviewNotificationPermissionsJobService();
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

        final int rescheduleTimeMillis = 350;  // arbitrary number

        // attempt to schedule the job
        ReviewNotificationPermissionsJobService.scheduleJob(mContext, rescheduleTimeMillis);
        verify(mMockJobScheduler, times(1)).schedule(mJobInfoCaptor.capture());

        // verify various properties of the job that is passed in to the job scheduler
        JobInfo jobInfo = mJobInfoCaptor.getValue();
        assertEquals(ReviewNotificationPermissionsJobService.JOB_ID, jobInfo.getId());
        assertEquals(rescheduleTimeMillis, jobInfo.getMinLatencyMillis());
        assertTrue(jobInfo.isPersisted());  // should continue after reboot
        assertFalse(jobInfo.isPeriodic());  // one time
    }

    @Test
    public void testOnStartJob() {
        // the job need not be persisted after it does its work, so it'll return
        // false
        assertFalse(mJobService.onStartJob(mJobParams));

        // verify that starting the job causes the notification to be sent
        verify(mMockNotificationManagerInternal).sendReviewPermissionsNotification();
    }
}
