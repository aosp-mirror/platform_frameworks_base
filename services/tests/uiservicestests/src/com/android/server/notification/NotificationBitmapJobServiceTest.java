/**
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
package com.android.server.notification;

import static com.google.common.truth.Truth.assertThat;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotSame;
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

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;

@RunWith(AndroidTestingRunner.class)
public class NotificationBitmapJobServiceTest extends UiServiceTestCase {
    private NotificationBitmapJobService mJobService;

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
        // Set up JobScheduler mock
        mJobService = new NotificationBitmapJobService();
        mJobService.attachBaseContext(mContext);
        mJobService.onCreate();
        mJobService.onBind(/* intent= */ null);  // Create JobServiceEngine within JobService.

        when(mMockJobScheduler.forNamespace(NotificationBitmapJobService.TAG))
                .thenReturn(mMockJobScheduler);
        mContext.addMockSystemService(JobScheduler.class, mMockJobScheduler);

        // Add NotificationManagerInternal to LocalServices
        LocalServices.removeServiceForTest(NotificationManagerInternal.class);
        LocalServices.addService(NotificationManagerInternal.class,
                mMockNotificationManagerInternal);
    }

    @Test
    public void testScheduleJob_withCorrectJobInfo() {
        when(mMockJobScheduler.getPendingJob(anyInt())).thenReturn(null);
        mJobService.scheduleJob(mContext);

        // Verify that the correct JobInfo was passed into JobScheduler
        verify(mMockJobScheduler, times(1)).schedule(mJobInfoCaptor.capture());
        JobInfo jobInfo = mJobInfoCaptor.getValue();

        assertThat(jobInfo.getId()).isEqualTo(NotificationBitmapJobService.BASE_JOB_ID);
        assertThat(jobInfo.isRequireDeviceIdle()).isTrue();
        assertThat(jobInfo.getMinLatencyMillis()).isGreaterThan(0);
    }

    @Test
    public void testOnStartJob_returnTrue() {
        assertThat(mJobService.onStartJob(mJobParams)).isTrue();
    }

    @Test
    public void testGetTimeUntilRemoval_beforeToday2am_returnTimeUntilToday2am() {
        ZoneId zoneId = ZoneId.systemDefault();
        ZonedDateTime now = Instant.now().atZone(zoneId);
        LocalDate today = now.toLocalDate();

        LocalTime oneAM = LocalTime.of(/* hour= */ 1, /* minute= */ 0);
        LocalTime twoAM = LocalTime.of(/* hour= */ 2, /* minute= */ 0);

        ZonedDateTime today1AM = ZonedDateTime.of(today, oneAM, zoneId);
        ZonedDateTime today2AM = ZonedDateTime.of(today, twoAM, zoneId);
        ZonedDateTime tomorrow2AM = today2AM.plusDays(1);

        final long msUntilRemoval = mJobService.getTimeUntilRemoval(
                /* now= */ today1AM, today2AM, tomorrow2AM);

        assertThat(msUntilRemoval).isEqualTo(Duration.ofHours(1).toMillis());
    }

    @Test
    public void testGetTimeUntilRemoval_afterToday2am_returnTimeUntilTomorrow2am() {
        ZoneId zoneId = ZoneId.systemDefault();
        ZonedDateTime now = Instant.now().atZone(zoneId);
        LocalDate today = now.toLocalDate();

        LocalTime threeAM = LocalTime.of(/* hour= */ 3, /* minute= */ 0);
        LocalTime twoAM = LocalTime.of(/* hour= */ 2, /* minute= */ 0);

        ZonedDateTime today3AM = ZonedDateTime.of(today, threeAM, zoneId);
        ZonedDateTime today2AM = ZonedDateTime.of(today, twoAM, zoneId);
        ZonedDateTime tomorrow2AM = today2AM.plusDays(1);

        final long msUntilRemoval = mJobService.getTimeUntilRemoval(/* now= */ today3AM,
                today2AM, tomorrow2AM);

        assertThat(msUntilRemoval).isEqualTo(Duration.ofHours(23).toMillis());
    }
}