/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.timedetector;

import static org.mockito.Mockito.mock;

import android.content.Context;
import android.os.HandlerThread;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.timezonedetector.TestHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TimeDetectorInternalImplTest {

    private Context mMockContext;
    private FakeTimeDetectorStrategy mFakeTimeDetectorStrategy;

    private TimeDetectorInternalImpl mTimeDetectorInternal;
    private HandlerThread mHandlerThread;
    private TestHandler mTestHandler;

    @Before
    public void setUp() {
        mMockContext = mock(Context.class);

        // Create a thread + handler for processing the work that the service posts.
        mHandlerThread = new HandlerThread("TimeDetectorInternalTest");
        mHandlerThread.start();
        mTestHandler = new TestHandler(mHandlerThread.getLooper());

        mFakeTimeDetectorStrategy = new FakeTimeDetectorStrategy();

        mTimeDetectorInternal = new TimeDetectorInternalImpl(
                mMockContext, mTestHandler, mFakeTimeDetectorStrategy);
    }

    @Test
    public void placeholder() {
      // A placeholder test until there are real methods to test.
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
        mHandlerThread.join();
    }
}
