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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockitoSession;

import android.app.usage.UsageEvents;
import android.app.usage.UsageEvents.Event;
import android.content.Context;
import android.os.SystemClock;
import android.text.format.DateUtils;

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
    private static final int TEST_USER_ID = 0;
    private static final String TEST_PACKAGE_NAME = "test.package";
    private static final long TIME_INTERVAL_MILLIS = DateUtils.DAY_IN_MILLIS;

    private UserUsageStatsService mService;
    private MockitoSession mMockitoSession;

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

        File dir = new File(InstrumentationRegistry.getContext().getCacheDir(), "test");
        mService = new UserUsageStatsService(mContext, TEST_USER_ID, dir, mStatsUpdatedListener);

        HashMap<String, Long> installedPkgs = new HashMap<>();
        installedPkgs.put(TEST_PACKAGE_NAME, System.currentTimeMillis());

        mService.init(System.currentTimeMillis(), installedPkgs);
    }

    @After
    public void tearDown() {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
    }

    @Test
    public void testReportEvent_eventAppearsInQueries() {
        Event event = new Event(ACTIVITY_RESUMED, SystemClock.elapsedRealtime());
        event.mPackage = TEST_PACKAGE_NAME;
        mService.reportEvent(event);

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
}
