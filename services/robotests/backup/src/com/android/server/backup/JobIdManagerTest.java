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
 * limitations under the License
 */

package com.android.server.backup;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.UserIdInt;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.testng.Assert.expectThrows;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class JobIdManagerTest {
    private static final int MIN_JOB_ID = 10;
    private static final int MAX_JOB_ID = 20;

    @UserIdInt private int mUserOneId;
    @UserIdInt private int mUserTwoId;

    @Before
    public void setUp() {
        mUserOneId = UserHandle.USER_SYSTEM;
        mUserTwoId = mUserOneId + 1;
    }

    @Test
    public void testGetJobIdForUserId_returnsDifferentJobIdsForDifferentUsers() {
        int jobIdOne = JobIdManager.getJobIdForUserId(MIN_JOB_ID, MAX_JOB_ID, mUserOneId);
        int jobIdTwo = JobIdManager.getJobIdForUserId(MIN_JOB_ID, MAX_JOB_ID, mUserTwoId);

        assertThat(jobIdOne).isNotEqualTo(jobIdTwo);
    }

    @Test
    public void testGetJobIdForUserId_returnsSameJobIdForSameUser() {
        int jobIdOne = JobIdManager.getJobIdForUserId(MIN_JOB_ID, MAX_JOB_ID, mUserOneId);
        int jobIdTwo = JobIdManager.getJobIdForUserId(MIN_JOB_ID, MAX_JOB_ID, mUserOneId);

        assertThat(jobIdOne).isEqualTo(jobIdTwo);
    }

    @Test
    public void testGetJobIdForUserId_throwsExceptionIfRangeIsExceeded() {
        expectThrows(
                RuntimeException.class,
                () -> JobIdManager.getJobIdForUserId(MIN_JOB_ID, MAX_JOB_ID,
                        MAX_JOB_ID + 1));
    }
}
