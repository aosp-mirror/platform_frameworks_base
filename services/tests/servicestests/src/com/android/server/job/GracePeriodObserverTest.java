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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.app.ActivityManagerInternal;
import android.content.Context;
import android.os.RemoteException;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.job.JobConcurrencyManager.GracePeriodObserver;
import com.android.server.pm.UserManagerInternal;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class GracePeriodObserverTest {
    private GracePeriodObserver mGracePeriodObserver;
    private UserManagerInternal mUserManagerInternal;
    private static final int FIRST_USER = 0;

    @BeforeClass
    public static void setUpOnce() {
        UserManagerInternal userManagerInternal = mock(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, userManagerInternal);
        ActivityManagerInternal activityManagerInternal = mock(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, activityManagerInternal);
    }

    @AfterClass
    public static void tearDownOnce() {
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
    }

    @Before
    public void setUp() {
        final Context context = ApplicationProvider.getApplicationContext();
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC);
        doReturn(FIRST_USER)
                .when(LocalServices.getService(ActivityManagerInternal.class)).getCurrentUserId();
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
        doReturn(true).when(mUserManagerInternal).exists(FIRST_USER);
        mGracePeriodObserver = new GracePeriodObserver(context);
    }

    @Test
    public void testGracePeriod() throws RemoteException {
        final int oldUser = FIRST_USER;
        final int newUser = 10;
        doReturn(true).when(mUserManagerInternal).exists(newUser);
        mGracePeriodObserver.onUserSwitchComplete(newUser);
        assertTrue(mGracePeriodObserver.isWithinGracePeriodForUser(oldUser));
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.offset(JobSchedulerService.sElapsedRealtimeClock,
                        Duration.ofMillis(mGracePeriodObserver.mGracePeriod));
        assertFalse(mGracePeriodObserver.isWithinGracePeriodForUser(oldUser));
    }

    @Test
    public void testCleanUp() throws RemoteException {
        final int removedUser = FIRST_USER;
        final int newUser = 10;
        mGracePeriodObserver.onUserSwitchComplete(newUser);

        final int sizeBefore = mGracePeriodObserver.mGracePeriodExpiration.size();
        doReturn(false).when(mUserManagerInternal).exists(removedUser);

        mGracePeriodObserver.onUserRemoved(removedUser);
        assertEquals(sizeBefore - 1, mGracePeriodObserver.mGracePeriodExpiration.size());
    }
}
