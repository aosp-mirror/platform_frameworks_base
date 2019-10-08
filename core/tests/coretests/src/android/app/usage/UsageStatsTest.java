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

import static android.app.usage.UsageEvents.Event.ACTIVITY_DESTROYED;
import static android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED;
import static android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED;
import static android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED;
import static android.app.usage.UsageEvents.Event.CONTINUING_FOREGROUND_SERVICE;
import static android.app.usage.UsageEvents.Event.DEVICE_SHUTDOWN;
import static android.app.usage.UsageEvents.Event.END_OF_DAY;
import static android.app.usage.UsageEvents.Event.FLUSH_TO_DISK;
import static android.app.usage.UsageEvents.Event.FOREGROUND_SERVICE_START;
import static android.app.usage.UsageEvents.Event.FOREGROUND_SERVICE_STOP;
import static android.app.usage.UsageEvents.Event.ROLLOVER_FOREGROUND_SERVICE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.usage.UsageEvents.Event;
import android.os.Parcel;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

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

        assertEquals(left.getFirstTimeStamp(), 99999);
    }

    @Test
    public void testLaterEndTimeTakesPriorityOnAdd() {
        left.mPackageName = "com.test";
        left.mEndTimeStamp = 100000;
        right.mPackageName = "com.test";
        right.mEndTimeStamp = 100001;

        left.add(right);

        assertEquals(left.getLastTimeStamp(), 100001);
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

        assertEquals(left.getLastTimeUsed(), 200001);
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

        assertEquals(left.getLastTimeUsed(), 200000);
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

        assertEquals(left.getTotalTimeInForeground(), 11);
    }

    @Test
    public void testParcelable() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;
        left.mTotalTimeInForeground = 10;

        left.mActivities.put(1, Event.ACTIVITY_RESUMED);
        left.mActivities.put(2, Event.ACTIVITY_RESUMED);
        left.mForegroundServices.put("com.test.service1", FOREGROUND_SERVICE_START);
        left.mForegroundServices.put("com.test.service2", FOREGROUND_SERVICE_START);

        Parcel p = Parcel.obtain();
        left.writeToParcel(p, 0);
        p.setDataPosition(0);
        right = UsageStats.CREATOR.createFromParcel(p);
        compareUsageStats(left, right);
    }

    @Test
    public void testActivity() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 200000, Event.ACTIVITY_RESUMED, 1);
        assertEquals(left.mLastTimeUsed, 200000);
        assertEquals(left.mLastTimeVisible, 200000);
        assertEquals(left.mActivities.get(1), Event.ACTIVITY_RESUMED);
        assertEquals(left.mLaunchCount, 1);
        assertEquals(left.mTotalTimeInForeground, 0);
        assertEquals(left.mTotalTimeVisible, 0);

        left.update("com.test.activity1", 350000, ACTIVITY_PAUSED, 1);
        assertEquals(left.mLastTimeUsed, 350000);
        assertEquals(left.mLastTimeVisible, 350000);
        assertEquals(left.mActivities.get(1), ACTIVITY_PAUSED);
        assertEquals(left.mTotalTimeInForeground, 350000 - 200000);
        assertEquals(left.mTotalTimeVisible, 350000 - 200000);

        left.update("com.test.activity1", 400000, ACTIVITY_STOPPED, 1);
        assertEquals(left.mLastTimeUsed, 350000);
        assertEquals(left.mLastTimeVisible, 400000);
        assertEquals(left.mActivities.get(1), ACTIVITY_STOPPED);
        assertEquals(left.mTotalTimeInForeground, 350000 - 200000);
        assertEquals(left.mTotalTimeVisible, 400000 - 200000);

        left.update("com.test.activity1", 500000, ACTIVITY_DESTROYED, 1);
        assertEquals(left.mLastTimeUsed, 350000);
        assertEquals(left.mLastTimeVisible, 400000);
        assertTrue(left.mActivities.indexOfKey(1) < 0);
        assertEquals(left.mTotalTimeInForeground, 350000 - 200000);
        assertEquals(left.mTotalTimeVisible, 400000 - 200000);
    }

    @Test
    public void testEvent_END_OF_DAY() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 100000, Event.ACTIVITY_RESUMED, 1);
        assertEquals(left.mLastTimeUsed, 100000);
        assertEquals(left.mLastTimeVisible, 100000);
        assertEquals(left.mActivities.get(1), Event.ACTIVITY_RESUMED);
        assertEquals(left.mLaunchCount, 1);

        left.update(null, 350000, END_OF_DAY, 0);
        assertEquals(left.mLastTimeUsed, 350000);
        assertEquals(left.mLastTimeVisible, 350000);
        assertEquals(left.mActivities.get(1), Event.ACTIVITY_RESUMED);
        assertEquals(left.mTotalTimeInForeground, 350000 - 100000);
        assertEquals(left.mTotalTimeVisible, 350000 - 100000);
    }

    @Test
    public void testEvent_ACTIVITY_PAUSED() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 100000, ACTIVITY_PAUSED, 1);
        assertEquals(left.mLastTimeUsed, 0);
        assertEquals(left.mLastTimeVisible, 100000);
        assertEquals(left.mActivities.get(1), ACTIVITY_PAUSED);

        left.update("com.test.activity1", 200000, Event.ACTIVITY_RESUMED, 1);
        assertEquals(left.mLastTimeUsed, 200000);
        assertEquals(left.mLastTimeVisible, 200000);
        assertEquals(left.mActivities.get(1), Event.ACTIVITY_RESUMED);
        assertEquals(left.mTotalTimeInForeground, 0);
        assertEquals(left.mTotalTimeVisible, 200000 - 100000);

        left.update("com.test.activity1", 300000, ACTIVITY_PAUSED, 1);
        assertEquals(left.mLastTimeUsed, 300000);
        assertEquals(left.mLastTimeVisible, 300000);
        assertEquals(left.mActivities.get(1), ACTIVITY_PAUSED);
        assertEquals(left.mTotalTimeInForeground, 300000 - 200000);
        assertEquals(left.mTotalTimeVisible, 300000 - 100000);

        left.update("com.test.activity1", 400000, ACTIVITY_STOPPED, 1);
        assertEquals(left.mLastTimeUsed, 300000);
        assertEquals(left.mLastTimeVisible, 400000);
        assertEquals(left.mActivities.get(1), ACTIVITY_STOPPED);
        assertEquals(left.mTotalTimeInForeground, 300000 - 200000);
        assertEquals(left.mTotalTimeVisible, 400000 - 100000);
    }

    @Test
    public void testEvent_CHANGE_TO_INVISIBLE() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 100000, ACTIVITY_RESUMED, 1);
        assertEquals(left.mLastTimeUsed, 100000);
        assertEquals(left.mLastTimeVisible, 100000);
        assertEquals(left.mActivities.get(1), ACTIVITY_RESUMED);

        left.update("com.test.activity1", 200000, ACTIVITY_STOPPED, 1);
        assertEquals(left.mLastTimeUsed, 200000);
        assertEquals(left.mLastTimeVisible, 200000);
        assertEquals(left.mActivities.get(1), ACTIVITY_STOPPED);
        assertEquals(left.mTotalTimeInForeground, 200000 - 100000);
        assertEquals(left.mTotalTimeVisible, 200000 - 100000);

        left.update("com.test.activity1", 300000, ACTIVITY_RESUMED, 1);
        assertEquals(left.mLastTimeUsed, 300000);
        assertEquals(left.mLastTimeVisible, 300000);
        assertEquals(left.mActivities.get(1), ACTIVITY_RESUMED);
        assertEquals(left.mTotalTimeInForeground, 200000 - 100000);
        assertEquals(left.mTotalTimeVisible, 200000 - 100000);
    }

    @Test
    public void testEvent_ACTIVITY_DESTROYED() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 100000, ACTIVITY_RESUMED, 1);
        assertEquals(left.mLastTimeUsed, 100000);
        assertEquals(left.mLastTimeVisible, 100000);
        assertEquals(left.mActivities.get(1), ACTIVITY_RESUMED);

        left.update("com.test.activity1", 200000, ACTIVITY_DESTROYED, 1);
        assertEquals(left.mLastTimeUsed, 200000);
        assertEquals(left.mLastTimeVisible, 200000);
        assertTrue(left.mActivities.indexOfKey(1) < 0);
        assertEquals(left.mTotalTimeInForeground, 200000 - 100000);
        assertEquals(left.mTotalTimeVisible, 200000 - 100000);
    }

    @Test
    public void testActivityEventOutOfOrder() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 100000, Event.ACTIVITY_RESUMED, 1);
        assertEquals(left.mLastTimeUsed, 100000);
        assertEquals(left.mLastTimeVisible, 100000);
        assertEquals(left.mActivities.get(1), Event.ACTIVITY_RESUMED);
        assertEquals(left.mLaunchCount, 1);
        assertEquals(left.mTotalTimeInForeground, 0);
        assertEquals(left.mTotalTimeVisible, 0);

        left.update("com.test.activity1", 200000, Event.ACTIVITY_RESUMED, 1);
        assertEquals(left.mLastTimeUsed, 200000);
        assertEquals(left.mLastTimeVisible, 200000);
        assertEquals(left.mActivities.get(1), Event.ACTIVITY_RESUMED);
        assertEquals(left.mLaunchCount, 2);
        assertEquals(left.mTotalTimeInForeground, 100000);
        assertEquals(left.mTotalTimeVisible, 100000 /*200000 - 100000*/);

        left.update("com.test.activity1", 250000, ACTIVITY_PAUSED, 1);
        assertEquals(left.mLastTimeUsed, 250000);
        assertEquals(left.mLastTimeVisible, 250000);
        assertEquals(left.mActivities.get(1), ACTIVITY_PAUSED);
        assertEquals(left.mTotalTimeInForeground, 150000);
        assertEquals(left.mTotalTimeVisible, 150000 /*250000 - 100000*/);

        left.update("com.test.activity1", 300000, ACTIVITY_PAUSED, 1);
        assertEquals(left.mLastTimeUsed, 250000);
        assertEquals(left.mLastTimeVisible, 300000);
        assertEquals(left.mActivities.get(1), ACTIVITY_PAUSED);
        assertEquals(left.mTotalTimeInForeground, 150000);
        assertEquals(left.mTotalTimeVisible, 200000 /*300000 - 100000*/);

        left.update("com.test.activity1", 350000, Event.ACTIVITY_RESUMED, 1);
        assertEquals(left.mLastTimeUsed, 350000);
        assertEquals(left.mLastTimeVisible, 350000);
        assertEquals(left.mActivities.get(1), Event.ACTIVITY_RESUMED);
        assertEquals(left.mTotalTimeInForeground, 150000);
        assertEquals(left.mTotalTimeVisible, 250000 /*350000 - 100000*/);

        left.update("com.test.activity1", 400000, END_OF_DAY, 1);
        assertEquals(left.mLastTimeUsed, 400000);
        assertEquals(left.mLastTimeVisible, 400000);
        assertEquals(left.mActivities.get(1), Event.ACTIVITY_RESUMED);
        assertEquals(left.mTotalTimeInForeground, 200000);
        assertEquals(left.mTotalTimeVisible, 300000 /*400000 - 100000*/);
    }

    @Test
    public void testTwoActivityEventSequence() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 100000, Event.ACTIVITY_RESUMED, 1);
        left.update("com.test.activity2", 100000, Event.ACTIVITY_RESUMED, 2);
        assertEquals(left.mLastTimeUsed, 100000);
        assertEquals(left.mLastTimeVisible, 100000);
        assertEquals(left.mActivities.get(1), Event.ACTIVITY_RESUMED);
        assertEquals(left.mActivities.get(2), Event.ACTIVITY_RESUMED);
        assertEquals(left.mLaunchCount, 2);

        left.update("com.test.activity1", 350000, ACTIVITY_PAUSED, 1);
        assertEquals(left.mLastTimeUsed, 350000);
        assertEquals(left.mLastTimeVisible, 350000);
        assertEquals(left.mActivities.get(1), ACTIVITY_PAUSED);
        assertEquals(left.mTotalTimeInForeground, 250000 /*350000 - 100000*/);
        assertEquals(left.mTotalTimeVisible, 250000 /*350000 - 100000*/);

        left.update("com.test.activity2", 450000, ACTIVITY_PAUSED, 2);
        assertEquals(left.mLastTimeUsed, 450000);
        assertEquals(left.mLastTimeVisible, 450000);
        assertEquals(left.mActivities.get(2), ACTIVITY_PAUSED);
        assertEquals(left.mTotalTimeInForeground, 250000 + 100000 /*450000 - 350000*/);
        assertEquals(left.mTotalTimeVisible, 250000 + 100000 /*450000 - 350000*/);

        left.update("com.test.activity1", 550000, ACTIVITY_STOPPED, 1);
        assertEquals(left.mLastTimeUsed, 450000);
        assertEquals(left.mLastTimeVisible, 550000);
        assertEquals(left.mActivities.get(1), ACTIVITY_STOPPED);
        assertEquals(left.mTotalTimeInForeground, 350000);
        assertEquals(left.mTotalTimeVisible, 350000 + 100000 /*550000 - 450000*/);

        left.update("com.test.activity2", 650000, ACTIVITY_STOPPED, 2);
        assertEquals(left.mLastTimeUsed, 450000);
        assertEquals(left.mLastTimeVisible, 650000);
        assertEquals(left.mActivities.get(2), ACTIVITY_STOPPED);
        assertEquals(left.mTotalTimeInForeground, 350000);
        assertEquals(left.mTotalTimeVisible, 450000 + 100000 /*650000 - 550000*/);
    }

    @Test
    public void testForegroundService() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.service1", 200000, FOREGROUND_SERVICE_START, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 200000);
        assertEquals(left.mForegroundServices.get("com.test.service1"),
                new Integer(FOREGROUND_SERVICE_START));

        left.update("com.test.service1", 350000, FOREGROUND_SERVICE_STOP, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 350000);
        assertEquals(left.mForegroundServices.get("com.test.service1"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 350000 - 200000);
    }

    @Test
    public void testEvent_CONTINUING_FOREGROUND_SERVICE() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.service1", 100000,
                CONTINUING_FOREGROUND_SERVICE, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 100000);
        assertEquals(left.mForegroundServices.get("com.test.service1"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));

        left.update("com.test.service1", 350000, FOREGROUND_SERVICE_STOP, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 350000);
        assertEquals(left.mForegroundServices.get("com.test.service1"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 350000 - 100000);
    }

    @Test
    public void testEvent_ROLLOVER_FOREGROUND_SERVICE() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.service1", 100000,
                CONTINUING_FOREGROUND_SERVICE, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 100000);
        assertEquals(left.mForegroundServices.get("com.test.service1"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));

        left.update(null, 350000, ROLLOVER_FOREGROUND_SERVICE, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 350000);
        assertEquals(left.mForegroundServices.get("com.test.service1"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));
        assertEquals(left.mTotalTimeForegroundServiceUsed, 350000 - 100000);
    }

    @Test
    public void testForegroundServiceEventSequence() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.service1", 100000,
                CONTINUING_FOREGROUND_SERVICE, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 100000);
        assertEquals(left.mForegroundServices.get("com.test.service1"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));
        assertEquals(left.mLaunchCount, 0);

        left.update("com.test.service1", 350000, FOREGROUND_SERVICE_STOP, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 350000);
        assertEquals(left.mForegroundServices.get("com.test.service1"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 250000 /*350000 - 100000*/);

        left.update("com.test.service1", 450000, FOREGROUND_SERVICE_START, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 450000);
        assertEquals(left.mForegroundServices.get("com.test.service1"),
                new Integer(FOREGROUND_SERVICE_START));
        assertEquals(left.mTotalTimeForegroundServiceUsed, 250000);

        left.update("com.test.service1", 500000, FOREGROUND_SERVICE_STOP, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 500000);
        assertEquals(left.mForegroundServices.get("com.test.service1"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed,
                250000 + 50000 /*500000 - 450000*/);
    }

    @Test
    public void testTwoServiceEventSequence() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.service1", 100000,
                CONTINUING_FOREGROUND_SERVICE, 0);
        left.update("com.test.service2", 100000,
                CONTINUING_FOREGROUND_SERVICE, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 100000);
        assertEquals(left.mForegroundServices.get("com.test.service1"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));
        assertEquals(left.mForegroundServices.get("com.test.service2"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));

        left.update("com.test.service1", 350000, FOREGROUND_SERVICE_STOP, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 350000);
        assertEquals(left.mForegroundServices.get("com.test.service1"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 250000 /*350000 - 100000*/);

        left.update("com.test.service2", 450000, FOREGROUND_SERVICE_STOP, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 450000);
        assertEquals(left.mForegroundServices.get("com.test.service2"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed,
                250000 + 100000 /*450000 - 350000*/);

        left.update(null, 500000, ROLLOVER_FOREGROUND_SERVICE, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 450000);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 350000);
    }

    @Test
    public void testTwoActivityAndTwoServiceEventSequence() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 100000, Event.ACTIVITY_RESUMED, 1);
        left.update("com.test.activity2", 100000, Event.ACTIVITY_RESUMED, 2);
        left.update("com.test.service1", 100000,
                CONTINUING_FOREGROUND_SERVICE, 0);
        left.update("com.test.service2", 100000,
                CONTINUING_FOREGROUND_SERVICE, 0);
        assertEquals(left.mLastTimeUsed, 100000);
        assertEquals(left.mLastTimeForegroundServiceUsed, 100000);
        assertEquals(left.mActivities.get(1), Event.ACTIVITY_RESUMED);
        assertEquals(left.mActivities.get(2), Event.ACTIVITY_RESUMED);
        assertEquals(left.mForegroundServices.get("com.test.service1"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));
        assertEquals(left.mForegroundServices.get("com.test.service2"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));
        assertEquals(left.mLaunchCount, 2);

        left.update("com.test.activity1", 350000, ACTIVITY_PAUSED, 1);
        assertEquals(left.mLastTimeUsed, 350000);
        assertEquals(left.mLastTimeVisible, 350000);
        assertEquals(left.mActivities.get(1), ACTIVITY_PAUSED);
        assertEquals(left.mTotalTimeInForeground, 250000 /*350000 - 100000*/);
        assertEquals(left.mTotalTimeVisible, 250000 /*350000 - 100000*/);

        left.update("com.test.service1", 400000, FOREGROUND_SERVICE_STOP, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 400000);
        assertEquals(left.mForegroundServices.get("com.test.service1"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 300000 /*400000 - 100000*/);

        left.update("com.test.activity2", 450000, ACTIVITY_PAUSED, 2);
        assertEquals(left.mLastTimeUsed, 450000);
        assertEquals(left.mLastTimeVisible, 450000);
        assertEquals(left.mActivities.get(2), ACTIVITY_PAUSED);
        assertEquals(left.mTotalTimeInForeground, 250000 + 100000 /*450000 - 350000*/);
        assertEquals(left.mTotalTimeVisible, 250000 + 100000 /*450000 - 350000*/);

        left.update("com.test.service2", 500000, FOREGROUND_SERVICE_STOP, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 500000);
        assertEquals(left.mForegroundServices.get("com.test.service2"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed,
                300000 + 100000 /*500000 - 400000*/);


        left.update(null, 550000, END_OF_DAY, 0);
        assertEquals(left.mLastTimeUsed, 450000);
        assertEquals(left.mLastTimeVisible, 550000);
        assertEquals(left.mTotalTimeInForeground, 350000);
        assertEquals(left.mTotalTimeVisible, 450000);

        left.update(null, 550000, ROLLOVER_FOREGROUND_SERVICE, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 500000);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 400000);
    }

    @Test
    public void testEvent_DEVICE_SHUTDOWN() {
        testClosingEvent(DEVICE_SHUTDOWN);
    }

    @Test
    public void testEvent_FLUSH_TO_DISK() {
        testClosingEvent(FLUSH_TO_DISK);
    }

    private void testClosingEvent(int eventType) {
        // When these three closing events are received, all open activities/services need to be
        // closed and usage stats are updated.
        if (eventType != DEVICE_SHUTDOWN
                && eventType != FLUSH_TO_DISK) {
            fail("Closing eventType must be one of DEVICE_SHUTDOWN, FLUSH_TO_DISK");
        }

        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 100000, Event.ACTIVITY_RESUMED, 1);
        assertEquals(left.mLastTimeUsed, 100000);
        assertEquals(left.mLastTimeVisible, 100000);
        assertEquals(left.mActivities.get(1), Event.ACTIVITY_RESUMED);

        left.update("com.test.service1", 150000, FOREGROUND_SERVICE_START, 0);
        assertEquals(left.mLastTimeForegroundServiceUsed, 150000);
        assertEquals(left.mForegroundServices.get("com.test.service1"),
                new Integer(FOREGROUND_SERVICE_START));

        left.update(null, 200000, eventType, 0);
        assertEquals(left.mLastTimeUsed, 200000);
        assertEquals(left.mLastTimeVisible, 200000);
        assertEquals(left.mTotalTimeInForeground, 200000 - 100000);
        assertEquals(left.mTotalTimeVisible, 200000 - 100000);
        assertEquals(left.mLastTimeForegroundServiceUsed, 200000);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 200000 - 150000);
    }

    void compareUsageStats(UsageStats us1, UsageStats us2) {
        assertEquals(us1.mPackageName, us2.mPackageName);
        assertEquals(us1.mBeginTimeStamp, us2.mBeginTimeStamp);
        assertEquals(us1.mLastTimeUsed, us2.mLastTimeUsed);
        assertEquals(us1.mLastTimeVisible, us2.mLastTimeVisible);
        assertEquals(us1.mLastTimeForegroundServiceUsed, us2.mLastTimeForegroundServiceUsed);
        assertEquals(us1.mTotalTimeInForeground, us2.mTotalTimeInForeground);
        assertEquals(us1.mTotalTimeForegroundServiceUsed, us2.mTotalTimeForegroundServiceUsed);
        assertEquals(us1.mAppLaunchCount, us2.mAppLaunchCount);
        assertEquals(us1.mActivities.size(),
                us2.mActivities.size());
        for (int i = 0; i < us1.mActivities.size(); i++) {
            assertEquals(us1.mActivities.keyAt(i),
                    us2.mActivities.keyAt(i));
            assertEquals(us1.mActivities.valueAt(i),
                    us2.mActivities.valueAt(i));
        }
        assertEquals(us1.mForegroundServices.size(),
                us2.mForegroundServices.size());
        for (int i = 0; i < us1.mForegroundServices.size(); i++) {
            assertEquals(us1.mForegroundServices.keyAt(i),
                    us2.mForegroundServices.keyAt(i));
            assertEquals(us1.mForegroundServices.valueAt(i),
                    us2.mForegroundServices.valueAt(i));
        }
        assertEquals(us1.mChooserCounts, us2.mChooserCounts);
    }
}
