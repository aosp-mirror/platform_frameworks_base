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

package com.android.server.usage;

import static android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED;
import static android.app.usage.UsageEvents.Event.APP_COMPONENT_USED;
import static android.app.usage.UsageEvents.Event.NOTIFICATION_SEEN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockitoSession;

import android.app.usage.UsageEvents;
import android.app.usage.UsageEvents.Event;
import android.content.Context;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.usage.UserUsageStatsService.StatsUpdatedListener;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.File;
import java.util.HashMap;

@RunWith(AndroidJUnit4.class)
public class UserUsageStatsServiceTest {
    private static final String TAG = UserUsageStatsServiceTest.class.getSimpleName();

    private static final int TEST_USER_ID = 0;
    private static final String TEST_PACKAGE_NAME = "test.package";
    private static final long TIME_INTERVAL_MILLIS = DateUtils.DAY_IN_MILLIS;

    private UserUsageStatsService mService;
    private MockitoSession mMockitoSession;

    private File mDir;

    @Mock
    private Context mContext;
    @Mock
    private StatsUpdatedListener mStatsUpdatedListener;

    @Before
    public void setUp() {
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();

        // Deleting in tearDown() doesn't always work, so adding a unique suffix to each test
        // directory to ensure sequential test runs don't interfere with each other.
        mDir = new File(InstrumentationRegistry.getContext().getCacheDir(),
                "test_" + System.currentTimeMillis());
        mService = new UserUsageStatsService(mContext, TEST_USER_ID, mDir, mStatsUpdatedListener);

        HashMap<String, Long> installedPkgs = new HashMap<>();
        installedPkgs.put(TEST_PACKAGE_NAME, System.currentTimeMillis());

        mService.init(System.currentTimeMillis(), installedPkgs, true);
    }

    @After
    public void tearDown() {
        if (mDir != null && mDir.exists() && !mDir.delete()) {
            Log.d(TAG, "Failed to delete test directory");
        }
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
    }

    @Test
    public void testReportEvent_eventAppearsInQueries() {
        Event event = new Event(ACTIVITY_RESUMED, SystemClock.elapsedRealtime());
        event.mPackage = TEST_PACKAGE_NAME;
        mService.reportEvent(event);

        // Force persist the event instead of waiting for it to be processed on the handler.
        mService.persistActiveStats();

        long now = System.currentTimeMillis();
        long startTime = now - TIME_INTERVAL_MILLIS;
        UsageEvents events = mService.queryEventsForPackage(
                startTime, now, TEST_PACKAGE_NAME, false /* includeTaskRoot */);

        boolean hasTestEvent = false;
        while (events != null && events.hasNextEvent()) {
            Event outEvent = new Event();
            events.getNextEvent(outEvent);
            if (outEvent.mEventType == ACTIVITY_RESUMED) {
                hasTestEvent = true;
            }
        }
        assertTrue(hasTestEvent);
    }

    @Test
    public void testReportEvent_packageUsedEventNotTracked() {
        // For APP_COMPONENT_USED event, the time stamp should have been converted to current time
        // before reported here.
        Event event = new Event(APP_COMPONENT_USED, System.currentTimeMillis());
        event.mPackage = TEST_PACKAGE_NAME;
        mService.reportEvent(event);

        // Force persist the event instead of waiting for it to be processed on the handler.
        mService.persistActiveStats();

        long now = System.currentTimeMillis();
        long startTime = now - TIME_INTERVAL_MILLIS;
        UsageEvents events = mService.queryEventsForPackage(
                startTime, now, TEST_PACKAGE_NAME, false /* includeTaskRoot */);

        boolean hasTestEvent = false;
        while (events != null && events.hasNextEvent()) {
            Event outEvent = new Event();
            events.getNextEvent(outEvent);
            if (outEvent.mEventType == APP_COMPONENT_USED) {
                hasTestEvent = true;
            }
        }
        assertFalse(hasTestEvent);
    }

    @Test
    public void testQueryEarliestEventsForPackage() {
        Event event1 = new Event(NOTIFICATION_SEEN, SystemClock.elapsedRealtime());
        event1.mPackage = TEST_PACKAGE_NAME;
        mService.reportEvent(event1);
        Event event2 = new Event(ACTIVITY_RESUMED, SystemClock.elapsedRealtime());
        event2.mPackage = TEST_PACKAGE_NAME;
        mService.reportEvent(event2);

        // Force persist the events instead of waiting for them to be processed on the handler.
        mService.persistActiveStats();

        long now = System.currentTimeMillis();
        long startTime = now - TIME_INTERVAL_MILLIS;
        UsageEvents events = mService.queryEarliestEventsForPackage(
                startTime, now, TEST_PACKAGE_NAME, ACTIVITY_RESUMED);

        assertNotNull(events);
        boolean hasTestEvent = false;
        int count = 0;
        while (events.hasNextEvent()) {
            count++;
            Event outEvent = new Event();
            events.getNextEvent(outEvent);
            if (outEvent.mEventType == ACTIVITY_RESUMED) {
                hasTestEvent = true;
            }
        }
        assertTrue(hasTestEvent);
        assertEquals(2, count);
    }

    /** Tests that the API works as expected even with the caching system. */
    @Test
    public void testQueryEarliestEventsForPackage_Caching() throws Exception {
        final long forcedDiff = 5000;
        Event event1 = new Event(NOTIFICATION_SEEN, SystemClock.elapsedRealtime());
        event1.mPackage = TEST_PACKAGE_NAME;
        mService.reportEvent(event1);
        final long event1ReportTime = System.currentTimeMillis();
        Thread.sleep(forcedDiff);
        Event event2 = new Event(ACTIVITY_RESUMED, SystemClock.elapsedRealtime());
        event2.mPackage = TEST_PACKAGE_NAME;
        mService.reportEvent(event2);
        final long event2ReportTime = System.currentTimeMillis();

        // Force persist the events instead of waiting for them to be processed on the handler.
        mService.persistActiveStats();

        long now = System.currentTimeMillis();
        long startTime = now - forcedDiff * 2;
        UsageEvents events = mService.queryEarliestEventsForPackage(
                startTime, now, TEST_PACKAGE_NAME, ACTIVITY_RESUMED);

        assertNotNull(events);
        boolean hasTestEvent = false;
        int count = 0;
        while (events.hasNextEvent()) {
            count++;
            Event outEvent = new Event();
            events.getNextEvent(outEvent);
            if (outEvent.mEventType == ACTIVITY_RESUMED) {
                hasTestEvent = true;
            }
        }
        assertTrue(hasTestEvent);
        assertEquals(2, count);

        // Query again
        events = mService.queryEarliestEventsForPackage(
                startTime, now, TEST_PACKAGE_NAME, ACTIVITY_RESUMED);

        assertNotNull(events);
        hasTestEvent = false;
        count = 0;
        while (events.hasNextEvent()) {
            count++;
            Event outEvent = new Event();
            events.getNextEvent(outEvent);
            if (outEvent.mEventType == ACTIVITY_RESUMED) {
                hasTestEvent = true;
            }
        }
        assertTrue(hasTestEvent);
        assertEquals(2, count);

        // Query around just the first event
        now = event1ReportTime;
        startTime = now - forcedDiff * 2;
        events = mService.queryEarliestEventsForPackage(
                startTime, now, TEST_PACKAGE_NAME, ACTIVITY_RESUMED);

        assertNotNull(events);
        hasTestEvent = false;
        count = 0;
        while (events.hasNextEvent()) {
            count++;
            Event outEvent = new Event();
            events.getNextEvent(outEvent);
            if (outEvent.mEventType == ACTIVITY_RESUMED) {
                hasTestEvent = true;
            }
        }
        assertFalse(hasTestEvent);
        assertEquals(1, count);

        // Shift query around the first event, still exclude the second
        now = event1ReportTime + forcedDiff / 2;
        startTime = event1ReportTime - forcedDiff / 2;
        events = mService.queryEarliestEventsForPackage(
                startTime, now, TEST_PACKAGE_NAME, ACTIVITY_RESUMED);

        assertNotNull(events);
        hasTestEvent = false;
        count = 0;
        while (events.hasNextEvent()) {
            count++;
            Event outEvent = new Event();
            events.getNextEvent(outEvent);
            if (outEvent.mEventType == ACTIVITY_RESUMED) {
                hasTestEvent = true;
            }
        }
        assertFalse(hasTestEvent);
        assertEquals(1, count);

        // Shift query around the second event only
        now = event2ReportTime + 1;
        startTime = event1ReportTime + forcedDiff / 4;
        events = mService.queryEarliestEventsForPackage(
                startTime, now, TEST_PACKAGE_NAME, ACTIVITY_RESUMED);

        assertNotNull(events);
        hasTestEvent = false;
        count = 0;
        while (events.hasNextEvent()) {
            count++;
            Event outEvent = new Event();
            events.getNextEvent(outEvent);
            if (outEvent.mEventType == ACTIVITY_RESUMED) {
                hasTestEvent = true;
            }
        }
        assertTrue(hasTestEvent);
        assertEquals(1, count);

        // Shift query around both events
        now = event2ReportTime + 1;
        startTime = now - forcedDiff * 2;
        events = mService.queryEarliestEventsForPackage(
                startTime, now, TEST_PACKAGE_NAME, ACTIVITY_RESUMED);

        assertNotNull(events);
        hasTestEvent = false;
        count = 0;
        while (events.hasNextEvent()) {
            count++;
            Event outEvent = new Event();
            events.getNextEvent(outEvent);
            if (outEvent.mEventType == ACTIVITY_RESUMED) {
                hasTestEvent = true;
            }
        }
        assertTrue(hasTestEvent);
        assertEquals(2, count);

        // Query around just the first event and then shift end time to include second event
        now = event1ReportTime;
        startTime = now - forcedDiff * 2;
        events = mService.queryEarliestEventsForPackage(
                startTime, now, TEST_PACKAGE_NAME, ACTIVITY_RESUMED);

        assertNotNull(events);
        hasTestEvent = false;
        count = 0;
        while (events.hasNextEvent()) {
            count++;
            Event outEvent = new Event();
            events.getNextEvent(outEvent);
            if (outEvent.mEventType == ACTIVITY_RESUMED) {
                hasTestEvent = true;
            }
        }
        assertFalse(hasTestEvent);
        assertEquals(1, count);

        now = event2ReportTime + 1;
        events = mService.queryEarliestEventsForPackage(
                startTime, now, TEST_PACKAGE_NAME, ACTIVITY_RESUMED);

        assertNotNull(events);
        hasTestEvent = false;
        count = 0;
        while (events.hasNextEvent()) {
            count++;
            Event outEvent = new Event();
            events.getNextEvent(outEvent);
            if (outEvent.mEventType == ACTIVITY_RESUMED) {
                hasTestEvent = true;
            }
        }
        assertTrue(hasTestEvent);
        assertEquals(2, count);
    }
}
