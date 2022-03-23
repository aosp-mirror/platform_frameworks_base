/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.timezonedetector;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.os.HandlerThread;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class TimeZoneDetectorInternalImplTest {

    private static final List<String> ARBITRARY_ZONE_IDS = Arrays.asList("TestZoneId");

    private Context mMockContext;
    private FakeTimeZoneDetectorStrategy mFakeTimeZoneDetectorStrategy;

    private TimeZoneDetectorInternalImpl mTimeZoneDetectorInternal;
    private HandlerThread mHandlerThread;
    private TestHandler mTestHandler;


    @Before
    public void setUp() {
        mMockContext = mock(Context.class);

        // Create a thread + handler for processing the work that the service posts.
        mHandlerThread = new HandlerThread("TimeZoneDetectorInternalTest");
        mHandlerThread.start();
        mTestHandler = new TestHandler(mHandlerThread.getLooper());

        mFakeTimeZoneDetectorStrategy = new FakeTimeZoneDetectorStrategy();

        mTimeZoneDetectorInternal = new TimeZoneDetectorInternalImpl(
                mMockContext, mTestHandler, mFakeTimeZoneDetectorStrategy);
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
        mHandlerThread.join();
    }

    @Test
    public void testSuggestGeolocationTimeZone() throws Exception {
        GeolocationTimeZoneSuggestion timeZoneSuggestion = createGeolocationTimeZoneSuggestion();
        mTimeZoneDetectorInternal.suggestGeolocationTimeZone(timeZoneSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        mTestHandler.waitForMessagesToBeProcessed();
        mFakeTimeZoneDetectorStrategy.verifySuggestGeolocationTimeZoneCalled(timeZoneSuggestion);
    }

    @Test
    public void testAddDumpable() throws Exception {
        Dumpable stubbedDumpable = mock(Dumpable.class);

        mTimeZoneDetectorInternal.addDumpable(stubbedDumpable);
        mTestHandler.assertTotalMessagesEnqueued(0);

        mFakeTimeZoneDetectorStrategy.verifyHasDumpable(stubbedDumpable);
    }

    @Test
    public void testAddConfigurationListener() throws Exception {
        boolean[] changeCalled = new boolean[2];
        mTimeZoneDetectorInternal.addConfigurationListener(() -> changeCalled[0] = true);
        mTimeZoneDetectorInternal.addConfigurationListener(() -> changeCalled[1] = true);

        mFakeTimeZoneDetectorStrategy.simulateConfigurationChangeForTests();

        assertTrue(changeCalled[0]);
        assertTrue(changeCalled[1]);
    }

    private static GeolocationTimeZoneSuggestion createGeolocationTimeZoneSuggestion() {
        return new GeolocationTimeZoneSuggestion(ARBITRARY_ZONE_IDS);
    }
}
