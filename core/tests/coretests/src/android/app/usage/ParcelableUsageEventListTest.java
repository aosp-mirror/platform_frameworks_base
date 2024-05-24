/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.app.usage;

import static android.view.Surface.ROTATION_90;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

import android.app.usage.UsageEvents.Event;
import android.content.res.Configuration;
import android.os.Parcel;
import android.os.PersistableBundle;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ParcelableUsageEventListTest {
    private static final int SMALL_TEST_EVENT_COUNT = 100;
    private static final int LARGE_TEST_EVENT_COUNT = 30000;

    private Random mRandom = new Random();

    @Test
    public void testNullList() throws Exception {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeParcelable(new ParcelableUsageEventList(null), 0);
            fail("Expected IllegalArgumentException with null list.");
        } catch (IllegalArgumentException expected) {
            // Expected.
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void testEmptyList() throws Exception {
        testParcelableUsageEventList(0);
    }

    @Test
    public void testSmallList() throws Exception {
        testParcelableUsageEventList(SMALL_TEST_EVENT_COUNT);
    }

    @Test
    public void testLargeList() throws Exception {
        testParcelableUsageEventList(LARGE_TEST_EVENT_COUNT);
    }

    private void testParcelableUsageEventList(int eventCount) throws Exception {
        List<Event> eventList = new ArrayList<>();
        for (int i = 0; i < eventCount; i++) {
            eventList.add(generateUsageEvent());
        }

        ParcelableUsageEventList slice;
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeParcelable(new ParcelableUsageEventList(eventList), 0);
            parcel.setDataPosition(0);
            slice = parcel.readParcelable(getClass().getClassLoader(),
                    ParcelableUsageEventList.class);
        } finally {
            parcel.recycle();
        }

        assertNotNull(slice);
        assertNotNull(slice.getList());
        assertEquals(eventCount, slice.getList().size());

        for (int i = 0; i < eventCount; i++) {
            compareUsageEvent(eventList.get(i), slice.getList().get(i));
        }
    }

    private Event generateUsageEvent() {
        final Event event = new Event();
        event.mEventType = mRandom.nextInt(Event.MAX_EVENT_TYPE + 1);
        event.mPackage = anyString();
        event.mClass = anyString();
        event.mTimeStamp = anyLong();
        event.mInstanceId = anyInt();
        event.mTimeStamp = anyLong();

        switch (event.mEventType) {
            case Event.CONFIGURATION_CHANGE:
                event.mConfiguration = new Configuration();
                event.mConfiguration.seq = anyInt();
                event.mConfiguration.screenLayout = Configuration.SCREENLAYOUT_ROUND_YES;
                event.mConfiguration.smallestScreenWidthDp = 100;
                event.mConfiguration.orientation = Configuration.ORIENTATION_LANDSCAPE;
                event.mConfiguration.windowConfiguration.setRotation(ROTATION_90);
                break;
            case Event.SHORTCUT_INVOCATION:
                event.mShortcutId = anyString();
                break;
            case Event.CHOOSER_ACTION:
                event.mAction = anyString();
                event.mContentType = anyString();
                event.mContentAnnotations = new String[mRandom.nextInt(10)];
                for (int i = 0; i < event.mContentAnnotations.length; i++) {
                    event.mContentAnnotations[i] = anyString();
                }
                break;
            case Event.STANDBY_BUCKET_CHANGED:
                event.mBucketAndReason = anyInt();
                break;
            case Event.NOTIFICATION_INTERRUPTION:
                event.mNotificationChannelId = anyString();
                break;
            case Event.LOCUS_ID_SET:
                event.mLocusId = anyString();
                break;
            case Event.USER_INTERACTION:
                PersistableBundle extras = new PersistableBundle();
                extras.putString(UsageStatsManager.EXTRA_EVENT_CATEGORY, anyString());
                extras.putString(UsageStatsManager.EXTRA_EVENT_ACTION, anyString());
                event.mExtras = extras;
                break;
        }

        event.mFlags = anyInt();
        return event;
    }

    private static void compareUsageEvent(Event ue1, Event ue2) {
        assertEquals(ue1.mPackage, ue2.mPackage);
        assertEquals(ue1.mClass, ue2.mClass);
        assertEquals(ue1.mTaskRootPackage, ue2.mTaskRootPackage);
        assertEquals(ue1.mTaskRootClass, ue2.mTaskRootClass);
        assertEquals(ue1.mInstanceId, ue2.mInstanceId);
        assertEquals(ue1.mEventType, ue2.mEventType);
        assertEquals(ue1.mTimeStamp, ue2.mTimeStamp);

        switch (ue1.mEventType) {
            case Event.CONFIGURATION_CHANGE:
                assertEquals(ue1.mConfiguration, ue2.mConfiguration);
                break;
            case Event.SHORTCUT_INVOCATION:
                assertEquals(ue1.mShortcutId, ue2.mShortcutId);
                break;
            case Event.CHOOSER_ACTION:
                assertEquals(ue1.mAction, ue2.mAction);
                assertEquals(ue1.mContentType, ue2.mContentType);
                assertTrue(Arrays.equals(ue1.mContentAnnotations, ue2.mContentAnnotations));
                break;
            case Event.STANDBY_BUCKET_CHANGED:
                assertEquals(ue1.mBucketAndReason, ue2.mBucketAndReason);
                break;
            case Event.NOTIFICATION_INTERRUPTION:
                assertEquals(ue1.mNotificationChannelId, ue1.mNotificationChannelId);
                break;
            case Event.LOCUS_ID_SET:
                assertEquals(ue1.mLocusId, ue2.mLocusId);
                break;
            case Event.USER_INTERACTION:
                final PersistableBundle extras1 = ue1.getExtras();
                final PersistableBundle extras2 = ue2.getExtras();
                assertEquals(extras1.getString(UsageStatsManager.EXTRA_EVENT_CATEGORY),
                        extras2.getString(UsageStatsManager.EXTRA_EVENT_CATEGORY));
                assertEquals(extras1.getString(UsageStatsManager.EXTRA_EVENT_ACTION),
                        extras2.getString(UsageStatsManager.EXTRA_EVENT_ACTION));
                break;
        }

        assertEquals(ue1.mFlags, ue2.mFlags);
    }
}
