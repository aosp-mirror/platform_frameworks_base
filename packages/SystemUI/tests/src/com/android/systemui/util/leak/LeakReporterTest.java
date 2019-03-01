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

package com.android.systemui.util.leak;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.NotificationManager;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.PrintWriter;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class LeakReporterTest extends SysuiTestCase {

    private LeakDetector mLeakDetector;
    private LeakReporter mLeakReporter;
    private File mLeakDir;
    private File mLeakDump;
    private File mLeakHprof;
    private NotificationManager mNotificationManager;

    @Before
    public void setup() {
        mLeakDir = new File(mContext.getCacheDir(), LeakReporter.LEAK_DIR);
        mLeakDump = new File(mLeakDir, LeakReporter.LEAK_DUMP);
        mLeakHprof = new File(mLeakDir, LeakReporter.LEAK_HPROF);

        mNotificationManager = mock(NotificationManager.class);
        mContext.addMockSystemService(NotificationManager.class, mNotificationManager);

        mLeakDetector = mock(LeakDetector.class);
        doAnswer(invocation -> {
            invocation.<PrintWriter>getArgument(1).println("test");
            return null;
        }).when(mLeakDetector).dump(any(), any(), any());

        mLeakReporter = new LeakReporter(mContext, mLeakDetector, "test@example.com");
    }

    @After
    public void teardown() {
        mLeakDump.delete();
        mLeakHprof.delete();
        mLeakDir.delete();
    }

    @Ignore("slow")
    @Test
    public void testDump_postsNotification() {
        mLeakReporter.dumpLeak(5);
        verify(mNotificationManager).notify(any(), anyInt(), any());
    }

    @Ignore("slow")
    @Test
    public void testDump_Repeated() {
        mLeakReporter.dumpLeak(1);
        mLeakReporter.dumpLeak(2);
    }

    @Ignore("slow")
    @Test
    public void testDump_ProducesNonZeroFiles() {
        mLeakReporter.dumpLeak(5);

        assertTrue(mLeakDump.exists());
        assertTrue(mLeakDump.length() > 0);

        assertTrue(mLeakHprof.exists());
        assertTrue(mLeakHprof.length() > 0);
    }
}