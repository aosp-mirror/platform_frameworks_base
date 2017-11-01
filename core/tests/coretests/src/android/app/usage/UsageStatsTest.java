/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app.usage;

import static com.google.common.truth.Truth.assertThat;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class UsageStatsTest {
    private UsageStats left;
    private UsageStats right;

    @Before
    public void setUp() throws Exception {
        left = new UsageStats();
        right = new UsageStats();
    }

    @Test
    public void testEarlierBeginTimeTakesPriorityOnAdd() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;
        right.mPackageName = "com.test";
        right.mBeginTimeStamp = 99999;

        left.add(right);

        assertThat(left.getFirstTimeStamp()).isEqualTo(99999);
    }

    @Test
    public void testLaterEndTimeTakesPriorityOnAdd() {
        left.mPackageName = "com.test";
        left.mEndTimeStamp = 100000;
        right.mPackageName = "com.test";
        right.mEndTimeStamp = 100001;

        left.add(right);

        assertThat(left.getLastTimeStamp()).isEqualTo(100001);
    }

    @Test
    public void testLastUsedTimeIsOverriddenByLaterStats() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;
        left.mLastTimeUsed = 200000;
        right.mPackageName = "com.test";
        right.mBeginTimeStamp = 100001;
        right.mLastTimeUsed = 200001;

        left.add(right);

        assertThat(left.getLastTimeUsed()).isEqualTo(200001);
    }

    @Test
    public void testLastUsedTimeIsNotOverriddenByLaterStatsIfUseIsEarlier() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;
        left.mLastTimeUsed = 200000;
        right.mPackageName = "com.test";
        right.mBeginTimeStamp = 100001;
        right.mLastTimeUsed = 150000;

        left.add(right);

        assertThat(left.getLastTimeUsed()).isEqualTo(200000);
    }

    @Test
    public void testForegroundTimeIsSummed() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;
        left.mTotalTimeInForeground = 10;
        right.mPackageName = "com.test";
        right.mBeginTimeStamp = 100001;
        right.mTotalTimeInForeground = 1;

        left.add(right);

        assertThat(left.getTotalTimeInForeground()).isEqualTo(11);
    }
}
