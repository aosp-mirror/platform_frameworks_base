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

package com.android.server.job;


import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.graphics.drawable.Icon;
import android.os.UserHandle;

import com.android.server.LocalServices;
import com.android.server.job.controllers.JobStatus;
import com.android.server.notification.NotificationManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

public class JobNotificationCoordinatorTest {
    private static final String TEST_PACKAGE = "com.android.test";
    private static final String NOTIFICATION_CHANNEL_ID = "validNotificationChannelId";

    private MockitoSession mMockingSession;

    @Mock
    private NotificationManagerInternal mNotificationManagerInternal;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(LocalServices.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        doReturn(mNotificationManagerInternal)
                .when(() -> LocalServices.getService(NotificationManagerInternal.class));
        doNothing().when(mNotificationManagerInternal)
                .enqueueNotification(anyString(), anyString(), anyInt(), anyInt(), any(),
                        anyInt(), any(), anyInt());
        doNothing().when(mNotificationManagerInternal)
                .enqueueNotification(anyString(), anyString(), anyInt(), anyInt(), any(),
                        anyInt(), any(), anyInt());
        doReturn(mock(NotificationChannel.class)).when(mNotificationManagerInternal)
                .getNotificationChannel(anyString(), anyInt(), eq(NOTIFICATION_CHANNEL_ID));
    }

    @After
    public void tearDown() throws Exception {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void testParameterValidation() {
        final JobNotificationCoordinator coordinator = new JobNotificationCoordinator();
        final JobServiceContext jsc = createJobServiceContext();
        final int uid = 10123;
        final int pid = 42;
        final int notificationId = 23;

        try {
            coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId, null,
                    JobService.JOB_END_NOTIFICATION_POLICY_DETACH);
            fail("Successfully enqueued a null notification");
        } catch (NullPointerException e) {
            // Success
        }

        Notification notification = createValidNotification();
        doReturn(null).when(notification).getSmallIcon();
        try {
            coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId,
                    notification, JobService.JOB_END_NOTIFICATION_POLICY_DETACH);
            fail("Successfully enqueued a notification with no small icon");
        } catch (IllegalArgumentException e) {
            // Success
        }

        notification = createValidNotification();
        doReturn(null).when(notification).getChannelId();
        try {
            coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId,
                    notification, JobService.JOB_END_NOTIFICATION_POLICY_DETACH);
            fail("Successfully enqueued a notification with no valid channel");
        } catch (IllegalArgumentException e) {
            // Success
        }

        notification = createValidNotification();
        try {
            coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId,
                    notification, Integer.MAX_VALUE);
            fail("Successfully enqueued a notification with an invalid job end notification "
                    + "policy");
        } catch (IllegalArgumentException e) {
            // Success
        }
    }

    @Test
    public void testSingleJob_DetachOnStop() {
        final JobNotificationCoordinator coordinator = new JobNotificationCoordinator();
        final JobServiceContext jsc = createJobServiceContext();
        final Notification notification = createValidNotification();
        final int uid = 10123;
        final int pid = 42;
        final int notificationId = 23;

        coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId, notification,
                JobService.JOB_END_NOTIFICATION_POLICY_DETACH);
        verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(notification), eq(UserHandle.getUserId(uid)));

        coordinator.removeNotificationAssociation(jsc, JobParameters.STOP_REASON_UNDEFINED,
                jsc.getRunningJobLocked());
        verify(mNotificationManagerInternal, never())
                .cancelNotification(anyString(), anyString(), anyInt(), anyInt(), any(),
                        anyInt(), anyInt());
    }

    @Test
    public void testSingleJob_RemoveOnStop() {
        final JobNotificationCoordinator coordinator = new JobNotificationCoordinator();
        final JobServiceContext jsc = createJobServiceContext();
        final Notification notification = createValidNotification();
        final int uid = 10123;
        final int pid = 42;
        final int notificationId = 23;

        coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId, notification,
                JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);
        verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(notification), eq(UserHandle.getUserId(uid)));

        coordinator.removeNotificationAssociation(jsc, JobParameters.STOP_REASON_UNDEFINED,
                jsc.getRunningJobLocked());
        verify(mNotificationManagerInternal)
                .cancelNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(UserHandle.getUserId(uid)));
    }

    @Test
    public void testSingleJob_EnqueueDifferentNotificationId_DetachOnStop() {
        final JobNotificationCoordinator coordinator = new JobNotificationCoordinator();
        final JobServiceContext jsc = createJobServiceContext();
        final Notification notification1 = createValidNotification();
        final Notification notification2 = createValidNotification();
        final int uid = 10123;
        final int pid = 42;
        final int notificationId1 = 23;
        final int notificationId2 = 46;

        InOrder inOrder = inOrder(mNotificationManagerInternal);

        coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId1, notification1,
                JobService.JOB_END_NOTIFICATION_POLICY_DETACH);
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId1), eq(notification1), eq(UserHandle.getUserId(uid)));

        coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId2, notification2,
                JobService.JOB_END_NOTIFICATION_POLICY_DETACH);
        inOrder.verify(mNotificationManagerInternal, never())
                .cancelNotification(anyString(), anyString(), anyInt(), anyInt(), any(),
                        anyInt(), anyInt());
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId2), eq(notification2), eq(UserHandle.getUserId(uid)));
    }

    @Test
    public void testSingleJob_EnqueueDifferentNotificationId_RemoveOnStop() {
        final JobNotificationCoordinator coordinator = new JobNotificationCoordinator();
        final JobServiceContext jsc = createJobServiceContext();
        final Notification notification1 = createValidNotification();
        final Notification notification2 = createValidNotification();
        final int uid = 10123;
        final int pid = 42;
        final int notificationId1 = 23;
        final int notificationId2 = 46;

        InOrder inOrder = inOrder(mNotificationManagerInternal);

        coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId1, notification1,
                JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId1), eq(notification1), eq(UserHandle.getUserId(uid)));

        coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId2, notification2,
                JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);
        inOrder.verify(mNotificationManagerInternal)
                .cancelNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId1), eq(UserHandle.getUserId(uid)));
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId2), eq(notification2), eq(UserHandle.getUserId(uid)));
    }

    @Test
    public void testSingleJob_EnqueueSameNotificationId() {
        final JobNotificationCoordinator coordinator = new JobNotificationCoordinator();
        final JobServiceContext jsc = createJobServiceContext();
        final Notification notification1 = createValidNotification();
        final Notification notification2 = createValidNotification();
        final int uid = 10123;
        final int pid = 42;
        final int notificationId = 23;

        InOrder inOrder = inOrder(mNotificationManagerInternal);

        coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId, notification1,
                JobService.JOB_END_NOTIFICATION_POLICY_DETACH);
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(notification1), eq(UserHandle.getUserId(uid)));

        coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId, notification2,
                JobService.JOB_END_NOTIFICATION_POLICY_DETACH);
        inOrder.verify(mNotificationManagerInternal, never())
                .cancelNotification(anyString(), anyString(), anyInt(), anyInt(), any(),
                        anyInt(), anyInt());
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(notification2), eq(UserHandle.getUserId(uid)));
    }

    @Test
    public void testMultipleJobs_sameApp_EnqueueDifferentNotificationId() {
        final JobNotificationCoordinator coordinator = new JobNotificationCoordinator();
        final JobServiceContext jsc1 = createJobServiceContext();
        final JobServiceContext jsc2 = createJobServiceContext();
        final Notification notification1 = createValidNotification();
        final Notification notification2 = createValidNotification();
        final int uid = 10123;
        final int pid = 42;
        final int notificationId1 = 23;
        final int notificationId2 = 46;

        InOrder inOrder = inOrder(mNotificationManagerInternal);

        coordinator.enqueueNotification(jsc1, TEST_PACKAGE, pid, uid, notificationId1,
                notification1, JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId1), eq(notification1), eq(UserHandle.getUserId(uid)));

        coordinator.enqueueNotification(jsc2, TEST_PACKAGE, pid, uid, notificationId2,
                notification2, JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);
        inOrder.verify(mNotificationManagerInternal, never())
                .cancelNotification(anyString(), anyString(), anyInt(), anyInt(), any(),
                        anyInt(), anyInt());
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId2), eq(notification2), eq(UserHandle.getUserId(uid)));

        // Remove the first job. Only the first notification should be removed.
        coordinator.removeNotificationAssociation(jsc1, JobParameters.STOP_REASON_UNDEFINED,
                jsc1.getRunningJobLocked());
        inOrder.verify(mNotificationManagerInternal)
                .cancelNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId1), eq(UserHandle.getUserId(uid)));
        inOrder.verify(mNotificationManagerInternal, never())
                .cancelNotification(anyString(), anyString(), anyInt(), anyInt(), any(),
                        eq(notificationId2), anyInt());

        coordinator.removeNotificationAssociation(jsc2, JobParameters.STOP_REASON_UNDEFINED,
                jsc2.getRunningJobLocked());
        inOrder.verify(mNotificationManagerInternal)
                .cancelNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId2), eq(UserHandle.getUserId(uid)));
    }

    @Test
    public void testMultipleJobs_sameApp_EnqueueSameNotificationId() {
        final JobNotificationCoordinator coordinator = new JobNotificationCoordinator();
        final JobServiceContext jsc1 = createJobServiceContext();
        final JobServiceContext jsc2 = createJobServiceContext();
        final Notification notification1 = createValidNotification();
        final Notification notification2 = createValidNotification();
        final int uid = 10123;
        final int pid = 42;
        final int notificationId = 23;

        InOrder inOrder = inOrder(mNotificationManagerInternal);

        coordinator.enqueueNotification(jsc1, TEST_PACKAGE, pid, uid, notificationId, notification1,
                JobService.JOB_END_NOTIFICATION_POLICY_DETACH);
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(notification1), eq(UserHandle.getUserId(uid)));

        coordinator.enqueueNotification(jsc2, TEST_PACKAGE, pid, uid, notificationId, notification2,
                JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);
        inOrder.verify(mNotificationManagerInternal, never())
                .cancelNotification(anyString(), anyString(), anyInt(), anyInt(), any(),
                        anyInt(), anyInt());
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(notification2), eq(UserHandle.getUserId(uid)));

        // Remove the first job. The notification shouldn't be touched because of the 2nd job.
        coordinator.removeNotificationAssociation(jsc1, JobParameters.STOP_REASON_UNDEFINED,
                jsc1.getRunningJobLocked());
        inOrder.verify(mNotificationManagerInternal, never())
                .cancelNotification(anyString(), anyString(), anyInt(), anyInt(), any(),
                        anyInt(), anyInt());

        coordinator.removeNotificationAssociation(jsc2, JobParameters.STOP_REASON_UNDEFINED,
                jsc2.getRunningJobLocked());
        inOrder.verify(mNotificationManagerInternal)
                .cancelNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(UserHandle.getUserId(uid)));
    }

    @Test
    public void testMultipleJobs_sameApp_DifferentUsers() {
        final JobNotificationCoordinator coordinator = new JobNotificationCoordinator();
        final JobServiceContext jsc1 = createJobServiceContext();
        final JobServiceContext jsc2 = createJobServiceContext();
        final Notification notification1 = createValidNotification();
        final Notification notification2 = createValidNotification();
        final int uid1 = 10123;
        final int uid2 = 1010123;
        final int pid = 42;
        final int notificationId = 23;

        InOrder inOrder = inOrder(mNotificationManagerInternal);

        coordinator.enqueueNotification(jsc1, TEST_PACKAGE, pid, uid1, notificationId,
                notification1, JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid1), eq(pid), any(),
                        eq(notificationId), eq(notification1), eq(UserHandle.getUserId(uid1)));

        coordinator.enqueueNotification(jsc2, TEST_PACKAGE, pid, uid2, notificationId,
                notification2, JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);
        inOrder.verify(mNotificationManagerInternal, never())
                .cancelNotification(anyString(), anyString(), anyInt(), anyInt(), any(),
                        anyInt(), anyInt());
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid2), eq(pid), any(),
                        eq(notificationId), eq(notification2), eq(UserHandle.getUserId(uid2)));

        // Remove the first job. Only the first notification should be removed.
        coordinator.removeNotificationAssociation(jsc1, JobParameters.STOP_REASON_UNDEFINED,
                jsc1.getRunningJobLocked());
        inOrder.verify(mNotificationManagerInternal)
                .cancelNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid1), eq(pid), any(),
                        eq(notificationId), eq(UserHandle.getUserId(uid1)));
        inOrder.verify(mNotificationManagerInternal, never())
                .cancelNotification(anyString(), anyString(), eq(uid2), anyInt(), any(),
                        anyInt(), anyInt());

        coordinator.removeNotificationAssociation(jsc2, JobParameters.STOP_REASON_UNDEFINED,
                jsc2.getRunningJobLocked());
        inOrder.verify(mNotificationManagerInternal)
                .cancelNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid2), eq(pid), any(),
                        eq(notificationId), eq(UserHandle.getUserId(uid2)));
    }

    @Test
    public void testMultipleJobs_differentApps() {
        final JobNotificationCoordinator coordinator = new JobNotificationCoordinator();
        final String pkg1 = "pkg1";
        final String pkg2 = "pkg2";
        final JobServiceContext jsc1 = createJobServiceContext();
        final JobServiceContext jsc2 = createJobServiceContext();
        final Notification notification1 = createValidNotification();
        final Notification notification2 = createValidNotification();
        final int uid = 10123;
        final int pid = 42;
        final int notificationId = 23;

        InOrder inOrder = inOrder(mNotificationManagerInternal);

        coordinator.enqueueNotification(jsc1, pkg1, pid, uid, notificationId, notification1,
                JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(pkg1), eq(pkg1), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(notification1), eq(UserHandle.getUserId(uid)));

        coordinator.enqueueNotification(jsc2, pkg2, pid, uid, notificationId, notification2,
                JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);
        inOrder.verify(mNotificationManagerInternal, never())
                .cancelNotification(anyString(), anyString(), anyInt(), anyInt(), any(),
                        anyInt(), anyInt());
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(pkg2), eq(pkg2), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(notification2), eq(UserHandle.getUserId(uid)));

        // Remove the first job. Only the first notification should be removed.
        coordinator.removeNotificationAssociation(jsc1, JobParameters.STOP_REASON_UNDEFINED,
                jsc1.getRunningJobLocked());
        inOrder.verify(mNotificationManagerInternal)
                .cancelNotification(eq(pkg1), eq(pkg1), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(UserHandle.getUserId(uid)));
        inOrder.verify(mNotificationManagerInternal, never())
                .cancelNotification(anyString(), anyString(), eq(uid), anyInt(), any(),
                        anyInt(), anyInt());

        coordinator.removeNotificationAssociation(jsc2, JobParameters.STOP_REASON_UNDEFINED,
                jsc2.getRunningJobLocked());
        inOrder.verify(mNotificationManagerInternal)
                .cancelNotification(eq(pkg2), eq(pkg2), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(UserHandle.getUserId(uid)));
    }

    @Test
    public void testUserStop_SingleJob_DetachOnStop() {
        final JobNotificationCoordinator coordinator = new JobNotificationCoordinator();
        final JobServiceContext jsc = createJobServiceContext();
        final Notification notification = createValidNotification();
        final int uid = 10123;
        final int pid = 42;
        final int notificationId = 23;

        coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId, notification,
                JobService.JOB_END_NOTIFICATION_POLICY_DETACH);
        verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(notification), eq(UserHandle.getUserId(uid)));

        coordinator.removeNotificationAssociation(jsc, JobParameters.STOP_REASON_USER,
                jsc.getRunningJobLocked());
        verify(mNotificationManagerInternal)
                .cancelNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(UserHandle.getUserId(uid)));
    }

    @Test
    public void testUserStop_MultipleJobs_sameApp_EnqueueSameNotificationId_DetachOnStop() {
        final JobNotificationCoordinator coordinator = new JobNotificationCoordinator();
        final JobServiceContext jsc1 = createJobServiceContext();
        final JobServiceContext jsc2 = createJobServiceContext();
        final Notification notification1 = createValidNotification();
        final Notification notification2 = createValidNotification();
        final int uid = 10123;
        final int pid = 42;
        final int notificationId = 23;

        InOrder inOrder = inOrder(mNotificationManagerInternal);

        coordinator.enqueueNotification(jsc1, TEST_PACKAGE, pid, uid, notificationId, notification1,
                JobService.JOB_END_NOTIFICATION_POLICY_DETACH);
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(notification1), eq(UserHandle.getUserId(uid)));

        coordinator.enqueueNotification(jsc2, TEST_PACKAGE, pid, uid, notificationId, notification2,
                JobService.JOB_END_NOTIFICATION_POLICY_DETACH);
        inOrder.verify(mNotificationManagerInternal, never())
                .cancelNotification(anyString(), anyString(), anyInt(), anyInt(), any(),
                        anyInt(), anyInt());
        inOrder.verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(notification2), eq(UserHandle.getUserId(uid)));

        // Remove the first job. The notification shouldn't be touched because of the 2nd job.
        coordinator.removeNotificationAssociation(jsc1, JobParameters.STOP_REASON_USER,
                jsc1.getRunningJobLocked());
        inOrder.verify(mNotificationManagerInternal, never())
                .cancelNotification(anyString(), anyString(), anyInt(), anyInt(), any(),
                        anyInt(), anyInt());

        coordinator.removeNotificationAssociation(jsc2, JobParameters.STOP_REASON_USER,
                jsc2.getRunningJobLocked());
        inOrder.verify(mNotificationManagerInternal)
                .cancelNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(UserHandle.getUserId(uid)));
    }

    @Test
    public void testUserInitiatedJob_hasNotificationFlag() {
        final JobNotificationCoordinator coordinator = new JobNotificationCoordinator();
        final JobServiceContext jsc = createJobServiceContext();
        final JobStatus js = jsc.getRunningJobLocked();
        js.startedAsUserInitiatedJob = true;
        final Notification notification = createValidNotification();
        final int uid = 10123;
        final int pid = 42;
        final int notificationId = 23;

        coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId, notification,
                JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);
        verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(notification), eq(UserHandle.getUserId(uid)));
        assertNotEquals(notification.flags & Notification.FLAG_USER_INITIATED_JOB, 0);
    }

    @Test
    public void testNonUserInitiatedJob_doesNotHaveNotificationFlag() {
        final JobNotificationCoordinator coordinator = new JobNotificationCoordinator();
        final JobServiceContext jsc = createJobServiceContext();
        final Notification notification = createValidNotification();
        final int uid = 10123;
        final int pid = 42;
        final int notificationId = 23;

        coordinator.enqueueNotification(jsc, TEST_PACKAGE, pid, uid, notificationId, notification,
                JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);
        verify(mNotificationManagerInternal)
                .enqueueNotification(eq(TEST_PACKAGE), eq(TEST_PACKAGE), eq(uid), eq(pid), any(),
                        eq(notificationId), eq(notification), eq(UserHandle.getUserId(uid)));
        assertEquals(notification.flags & Notification.FLAG_USER_INITIATED_JOB, 0);
    }

    private JobServiceContext createJobServiceContext() {
        final JobServiceContext jsc = mock(JobServiceContext.class);
        doReturn(mock(JobStatus.class)).when(jsc).getRunningJobLocked();
        return jsc;
    }

    private Notification createValidNotification() {
        final Notification notification = mock(Notification.class);
        doReturn(mock(Icon.class)).when(notification).getSmallIcon();
        doReturn(NOTIFICATION_CHANNEL_ID).when(notification).getChannelId();
        return notification;
    }
}
