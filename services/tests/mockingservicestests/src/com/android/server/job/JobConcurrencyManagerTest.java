/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.UserHandle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.server.LocalServices;
import com.android.server.job.JobConcurrencyManager.GracePeriodObserver;
import com.android.server.job.controllers.JobStatus;
import com.android.server.pm.UserManagerInternal;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class JobConcurrencyManagerTest {
    private static final int UNAVAILABLE_USER = 0;
    private JobConcurrencyManager mJobConcurrencyManager;
    private UserManagerInternal mUserManagerInternal;
    private ActivityManagerInternal mActivityManagerInternal;
    private int mNextUserId;
    private GracePeriodObserver mGracePeriodObserver;
    private Context mContext;
    private Resources mResources;

    @BeforeClass
    public static void setUpOnce() {
        LocalServices.addService(UserManagerInternal.class, mock(UserManagerInternal.class));
        LocalServices.addService(
                ActivityManagerInternal.class, mock(ActivityManagerInternal.class));
    }

    @AfterClass
    public static void tearDownOnce() {
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
    }

    @Before
    public void setUp() {
        final JobSchedulerService jobSchedulerService = mock(JobSchedulerService.class);
        mContext = mock(Context.class);
        mResources = mock(Resources.class);
        doReturn(true).when(mResources).getBoolean(
                R.bool.config_jobSchedulerRestrictBackgroundUser);
        when(mContext.getResources()).thenReturn(mResources);
        doReturn(mContext).when(jobSchedulerService).getTestableContext();
        mJobConcurrencyManager = new JobConcurrencyManager(jobSchedulerService);
        mGracePeriodObserver = mock(GracePeriodObserver.class);
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mNextUserId = 10;
        mJobConcurrencyManager.mGracePeriodObserver = mGracePeriodObserver;
    }

    @Test
    public void testShouldRunAsFgUserJob_currentUser() {
        assertTrue(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createCurrentUser(false))));
    }

    @Test
    public void testShouldRunAsFgUserJob_currentProfile() {
        assertTrue(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createCurrentUser(true))));
    }

    @Test
    public void testShouldRunAsFgUserJob_primaryUser() {
        assertTrue(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createPrimaryUser(false))));
    }

    @Test
    public void testShouldRunAsFgUserJob_primaryProfile() {
        assertTrue(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createPrimaryUser(true))));
    }

    @Test
    public void testShouldRunAsFgUserJob_UnexpiredUser() {
        assertTrue(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createUnexpiredUser(false))));
    }

    @Test
    public void testShouldRunAsFgUserJob_UnexpiredProfile() {
        assertTrue(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createUnexpiredUser(true))));
    }

    @Test
    public void testShouldRunAsFgUserJob_restrictedUser() {
        assertFalse(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createRestrictedUser(false))));
    }

    @Test
    public void testShouldRunAsFgUserJob_restrictedProfile() {
        assertFalse(mJobConcurrencyManager.shouldRunAsFgUserJob(
                createJob(createRestrictedUser(true))));
    }

    private UserInfo createCurrentUser(boolean isProfile) {
        final UserInfo ui = createNewUser();
        doReturn(ui.id).when(mActivityManagerInternal).getCurrentUserId();
        return isProfile ? createNewProfile(ui) : ui;
    }

    private UserInfo createPrimaryUser(boolean isProfile) {
        final UserInfo ui = createNewUser();
        doReturn(true).when(ui).isPrimary();
        return isProfile ? createNewProfile(ui) : ui;
    }

    private UserInfo createUnexpiredUser(boolean isProfile) {
        final UserInfo ui = createNewUser();
        doReturn(true).when(mGracePeriodObserver).isWithinGracePeriodForUser(ui.id);
        return isProfile ? createNewProfile(ui) : ui;
    }

    private UserInfo createRestrictedUser(boolean isProfile) {
        final UserInfo ui = createNewUser();
        doReturn(UNAVAILABLE_USER).when(mActivityManagerInternal).getCurrentUserId();
        doReturn(false).when(ui).isPrimary();
        doReturn(false).when(mGracePeriodObserver).isWithinGracePeriodForUser(ui.id);
        return isProfile ? createNewProfile(ui) : ui;
    }

    private UserInfo createNewProfile(UserInfo parent) {
        final UserInfo ui = createNewUser();
        parent.profileGroupId = parent.id;
        ui.profileGroupId = parent.id;
        doReturn(true).when(ui).isProfile();
        return ui;
    }

    private UserInfo createNewUser() {
        final UserInfo ui = mock(UserInfo.class);
        ui.id = mNextUserId++;
        doReturn(ui).when(mUserManagerInternal).getUserInfo(ui.id);
        ui.profileGroupId = UserInfo.NO_PROFILE_GROUP_ID;
        return ui;
    }

    private static JobStatus createJob(UserInfo userInfo) {
        JobStatus jobStatus = JobStatus.createFromJobInfo(
                new JobInfo.Builder(1, new ComponentName("foo", "bar")).build(),
                userInfo.id * UserHandle.PER_USER_RANGE,
                null, userInfo.id, "JobConcurrencyManagerTest");
        return jobStatus;
    }
}
