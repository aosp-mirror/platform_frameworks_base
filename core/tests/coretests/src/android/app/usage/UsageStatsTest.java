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

import static android.app.usage.UsageEvents.Event.CONTINUE_PREVIOUS_DAY;
import static android.app.usage.UsageEvents.Event.CONTINUING_FOREGROUND_SERVICE;
import static android.app.usage.UsageEvents.Event.END_OF_DAY;
import static android.app.usage.UsageEvents.Event.FOREGROUND_SERVICE_START;
import static android.app.usage.UsageEvents.Event.FOREGROUND_SERVICE_STOP;
import static android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND;
import static android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND;
import static android.app.usage.UsageEvents.Event.ROLLOVER_FOREGROUND_SERVICE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.os.Parcel;
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

        left.mLastForegroundActivityEventMap.put("com.test.activity1", MOVE_TO_FOREGROUND);
        left.mLastForegroundActivityEventMap.put("com.test.activity2", MOVE_TO_FOREGROUND);
        left.mLastForegroundServiceEventMap.put("com.test.service1", FOREGROUND_SERVICE_START);
        left.mLastForegroundServiceEventMap.put("com.test.service2", FOREGROUND_SERVICE_START);

        Parcel p = Parcel.obtain();
        left.writeToParcel(p, 0);
        p.setDataPosition(0);
        right = UsageStats.CREATOR.createFromParcel(p);
        compareUsageStats(left, right);
    }

    @Test
    public void testForegroundActivity() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 200000, MOVE_TO_FOREGROUND);
        assertEquals(left.mLastTimeUsed, 200000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"),
                new Integer(MOVE_TO_FOREGROUND));
        assertEquals(left.mLaunchCount, 1);

        left.update("com.test.activity1", 350000, MOVE_TO_BACKGROUND);
        assertEquals(left.mLastTimeUsed, 350000);
        assertFalse(left.mLastForegroundActivityEventMap.containsKey("com.test.activity1"));
        assertEquals(left.mTotalTimeInForeground, 350000 - 200000);
    }

    @Test
    public void testEvent_CONTINUE_PREVIOUS_DAY() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 100000, CONTINUE_PREVIOUS_DAY);
        assertEquals(left.mLastTimeUsed, 100000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"),
                new Integer(CONTINUE_PREVIOUS_DAY));
        assertEquals(left.mLaunchCount, 0);

        left.update("com.test.activity1", 350000, MOVE_TO_BACKGROUND);
        assertEquals(left.mLastTimeUsed, 350000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"), null);
        assertEquals(left.mTotalTimeInForeground, 350000 - 100000);
    }

    @Test
    public void testEvent_END_OF_DAY() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 100000, CONTINUE_PREVIOUS_DAY);
        assertEquals(left.mLastTimeUsed, 100000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"),
                new Integer(CONTINUE_PREVIOUS_DAY));
        assertEquals(left.mLaunchCount, 0);

        left.update(null, 350000, END_OF_DAY);
        assertEquals(left.mLastTimeUsed, 350000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"),
                new Integer(END_OF_DAY));
        assertEquals(left.mTotalTimeInForeground, 350000 - 100000);
    }

    @Test
    public void testForegroundActivityEventSequence() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 100000, CONTINUE_PREVIOUS_DAY);
        assertEquals(left.mLastTimeUsed, 100000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"),
                new Integer(CONTINUE_PREVIOUS_DAY));
        assertEquals(left.mLaunchCount, 0);

        left.update("com.test.activity1", 350000, MOVE_TO_BACKGROUND);
        assertEquals(left.mLastTimeUsed, 350000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"), null);
        assertEquals(left.mTotalTimeInForeground, 250000 /*350000 - 100000*/);

        left.update("com.test.activity1", 450000, MOVE_TO_FOREGROUND);
        assertEquals(left.mLastTimeUsed, 450000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"),
                new Integer(MOVE_TO_FOREGROUND));
        assertEquals(left.mTotalTimeInForeground, 250000);

        left.update("com.test.activity1", 500000, MOVE_TO_BACKGROUND);
        assertEquals(left.mLastTimeUsed, 500000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"), null);
        assertEquals(left.mTotalTimeInForeground, 250000 + 50000 /*500000 - 450000*/);
    }

    @Test
    public void testForegroundActivityEventOutOfSequence() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 100000, CONTINUE_PREVIOUS_DAY);
        assertEquals(left.mLastTimeUsed, 100000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"),
                new Integer(CONTINUE_PREVIOUS_DAY));
        assertEquals(left.mLaunchCount, 0);

        left.update("com.test.activity1", 150000, MOVE_TO_FOREGROUND);
        assertEquals(left.mLastTimeUsed, 150000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"),
                new Integer(MOVE_TO_FOREGROUND));
        assertEquals(left.mLaunchCount, 1);
        assertEquals(left.mTotalTimeInForeground, 50000 /*150000 - 100000*/);

        left.update("com.test.activity1", 200000, MOVE_TO_FOREGROUND);
        assertEquals(left.mLastTimeUsed, 200000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"),
                new Integer(MOVE_TO_FOREGROUND));
        assertEquals(left.mLaunchCount, 2);
        assertEquals(left.mTotalTimeInForeground, 100000);

        left.update("com.test.activity1", 250000, MOVE_TO_BACKGROUND);
        assertEquals(left.mLastTimeUsed, 250000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"), null);
        assertEquals(left.mTotalTimeInForeground, 150000);

        left.update("com.test.activity1", 300000, MOVE_TO_BACKGROUND);
        assertEquals(left.mLastTimeUsed, 250000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"), null);
        assertEquals(left.mTotalTimeInForeground, 150000);

        left.update("com.test.activity1", 350000, MOVE_TO_FOREGROUND);
        assertEquals(left.mLastTimeUsed, 350000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"),
                new Integer(MOVE_TO_FOREGROUND));
        assertEquals(left.mTotalTimeInForeground, 150000);

        left.update("com.test.activity1", 400000, END_OF_DAY);
        assertEquals(left.mLastTimeUsed, 400000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"),
                new Integer(END_OF_DAY));
        assertEquals(left.mTotalTimeInForeground, 200000);
    }

    @Test
    public void testTwoActivityEventSequence() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 100000, CONTINUE_PREVIOUS_DAY);
        left.update("com.test.activity2", 100000, CONTINUE_PREVIOUS_DAY);
        assertEquals(left.mLastTimeUsed, 100000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"),
                new Integer(CONTINUE_PREVIOUS_DAY));
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity2"),
                new Integer(CONTINUE_PREVIOUS_DAY));
        assertEquals(left.mLaunchCount, 0);

        left.update("com.test.activity1", 350000, MOVE_TO_BACKGROUND);
        assertEquals(left.mLastTimeUsed, 350000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"), null);
        assertEquals(left.mTotalTimeInForeground, 250000 /*350000 - 100000*/);

        left.update("com.test.activity2", 450000, MOVE_TO_BACKGROUND);
        assertEquals(left.mLastTimeUsed, 450000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity2"), null);
        assertEquals(left.mTotalTimeInForeground, 250000 + 100000 /*450000 - 350000*/);

        left.update(null, 500000, END_OF_DAY);
        assertEquals(left.mLastTimeUsed, 450000);
        assertEquals(left.mTotalTimeInForeground, 350000);
    }

    @Test
    public void testForegroundService() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.service1", 200000, FOREGROUND_SERVICE_START);
        assertEquals(left.mLastTimeForegroundServiceUsed, 200000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service1"),
                new Integer(FOREGROUND_SERVICE_START));
        assertEquals(left.mLaunchCount, 0);

        left.update("com.test.service1", 350000, FOREGROUND_SERVICE_STOP);
        assertEquals(left.mLastTimeForegroundServiceUsed, 350000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service1"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 350000 - 200000);
    }

    @Test
    public void testEvent_CONTINUING_FOREGROUND_SERVICE() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.service1", 100000, CONTINUING_FOREGROUND_SERVICE);
        assertEquals(left.mLastTimeForegroundServiceUsed, 100000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service1"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));
        assertEquals(left.mLaunchCount, 0);

        left.update("com.test.service1", 350000, FOREGROUND_SERVICE_STOP);
        assertEquals(left.mLastTimeForegroundServiceUsed, 350000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service1"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 350000 - 100000);
    }

    @Test
    public void testEvent_ROLLOVER_FOREGROUND_SERVICE() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.service1", 100000,
                CONTINUING_FOREGROUND_SERVICE);
        assertEquals(left.mLastTimeForegroundServiceUsed, 100000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service1"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));
        assertEquals(left.mLaunchCount, 0);

        left.update(null, 350000, ROLLOVER_FOREGROUND_SERVICE);
        assertEquals(left.mLastTimeForegroundServiceUsed, 350000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service1"),
                new Integer(ROLLOVER_FOREGROUND_SERVICE));
        assertEquals(left.mTotalTimeForegroundServiceUsed, 350000 - 100000);
    }

    @Test
    public void testForegroundServiceEventSequence() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.service1", 100000,
                CONTINUING_FOREGROUND_SERVICE);
        assertEquals(left.mLastTimeForegroundServiceUsed, 100000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service1"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));
        assertEquals(left.mLaunchCount, 0);

        left.update("com.test.service1", 350000, FOREGROUND_SERVICE_STOP);
        assertEquals(left.mLastTimeForegroundServiceUsed, 350000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service1"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 250000 /*350000 - 100000*/);

        left.update("com.test.service1", 450000, FOREGROUND_SERVICE_START);
        assertEquals(left.mLastTimeForegroundServiceUsed, 450000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service1"),
                new Integer(FOREGROUND_SERVICE_START));
        assertEquals(left.mTotalTimeForegroundServiceUsed, 250000);

        left.update("com.test.service1", 500000, FOREGROUND_SERVICE_STOP);
        assertEquals(left.mLastTimeForegroundServiceUsed, 500000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service1"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 250000 + 50000 /*500000 - 450000*/);
    }

    @Test
    public void testTwoServiceEventSequence() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.service1", 100000,
                CONTINUING_FOREGROUND_SERVICE);
        left.update("com.test.service2", 100000,
                CONTINUING_FOREGROUND_SERVICE);
        assertEquals(left.mLastTimeForegroundServiceUsed, 100000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service1"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service2"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));
        assertEquals(left.mLaunchCount, 0);

        left.update("com.test.service1", 350000, FOREGROUND_SERVICE_STOP);
        assertEquals(left.mLastTimeForegroundServiceUsed, 350000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service1"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 250000 /*350000 - 100000*/);

        left.update("com.test.service2", 450000, FOREGROUND_SERVICE_STOP);
        assertEquals(left.mLastTimeForegroundServiceUsed, 450000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service2"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 250000 + 100000 /*450000 - 350000*/);

        left.update(null, 500000, ROLLOVER_FOREGROUND_SERVICE);
        assertEquals(left.mLastTimeForegroundServiceUsed, 450000);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 350000);
    }

    @Test
    public void testTwoActivityAndTwoServiceEventSequence() {
        left.mPackageName = "com.test";
        left.mBeginTimeStamp = 100000;

        left.update("com.test.activity1", 100000, CONTINUE_PREVIOUS_DAY);
        left.update("com.test.activity2", 100000, CONTINUE_PREVIOUS_DAY);
        left.update("com.test.service1", 100000,
                CONTINUING_FOREGROUND_SERVICE);
        left.update("com.test.service2", 100000,
                CONTINUING_FOREGROUND_SERVICE);
        assertEquals(left.mLastTimeUsed, 100000);
        assertEquals(left.mLastTimeForegroundServiceUsed, 100000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"),
                new Integer(CONTINUE_PREVIOUS_DAY));
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity2"),
                new Integer(CONTINUE_PREVIOUS_DAY));
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service1"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service2"),
                new Integer(CONTINUING_FOREGROUND_SERVICE));
        assertEquals(left.mLaunchCount, 0);

        left.update("com.test.activity1", 350000, MOVE_TO_BACKGROUND);
        assertEquals(left.mLastTimeUsed, 350000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity1"), null);
        assertEquals(left.mTotalTimeInForeground, 250000 /*350000 - 100000*/);

        left.update("com.test.service1", 400000, FOREGROUND_SERVICE_STOP);
        assertEquals(left.mLastTimeForegroundServiceUsed, 400000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service1"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 300000 /*400000 - 100000*/);

        left.update("com.test.activity2", 450000, MOVE_TO_BACKGROUND);
        assertEquals(left.mLastTimeUsed, 450000);
        assertEquals(left.mLastForegroundActivityEventMap.get("com.test.activity2"), null);
        assertEquals(left.mTotalTimeInForeground, 250000 + 100000 /*450000 - 350000*/);

        left.update("com.test.service2", 500000, FOREGROUND_SERVICE_STOP);
        assertEquals(left.mLastTimeForegroundServiceUsed, 500000);
        assertEquals(left.mLastForegroundServiceEventMap.get("com.test.service2"), null);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 300000 + 100000 /*500000 - 400000*/);


        left.update(null, 550000, END_OF_DAY);
        assertEquals(left.mLastTimeUsed, 450000);
        assertEquals(left.mTotalTimeInForeground, 350000);
        left.update(null, 550000, ROLLOVER_FOREGROUND_SERVICE);
        assertEquals(left.mLastTimeForegroundServiceUsed, 500000);
        assertEquals(left.mTotalTimeForegroundServiceUsed, 400000);
    }

    void compareUsageStats(UsageStats us1, UsageStats us2) {
        assertEquals(us1.mPackageName, us2.mPackageName);
        assertEquals(us1.mBeginTimeStamp, us2.mBeginTimeStamp);
        assertEquals(us1.mLastTimeUsed, us2.mLastTimeUsed);
        assertEquals(us1.mLastTimeForegroundServiceUsed, us2.mLastTimeForegroundServiceUsed);
        assertEquals(us1.mTotalTimeInForeground, us2.mTotalTimeInForeground);
        assertEquals(us1.mTotalTimeForegroundServiceUsed, us2.mTotalTimeForegroundServiceUsed);
        assertEquals(us1.mAppLaunchCount, us2.mAppLaunchCount);
        assertEquals(us1.mLastForegroundActivityEventMap.size(),
                us2.mLastForegroundActivityEventMap.size());
        for (int i = 0; i < us1.mLastForegroundActivityEventMap.size(); i++) {
            assertEquals(us1.mLastForegroundActivityEventMap.keyAt(i),
                    us2.mLastForegroundActivityEventMap.keyAt(i));
            assertEquals(us1.mLastForegroundActivityEventMap.valueAt(i),
                    us2.mLastForegroundActivityEventMap.valueAt(i));
        }
        assertEquals(us1.mLastForegroundServiceEventMap.size(),
                us2.mLastForegroundServiceEventMap.size());
        for (int i = 0; i < us1.mLastForegroundServiceEventMap.size(); i++) {
            assertEquals(us1.mLastForegroundServiceEventMap.keyAt(i),
                    us2.mLastForegroundServiceEventMap.keyAt(i));
            assertEquals(us1.mLastForegroundServiceEventMap.valueAt(i),
                    us2.mLastForegroundServiceEventMap.valueAt(i));
        }
        assertEquals(us1.mChooserCounts, us2.mChooserCounts);
    }
}
