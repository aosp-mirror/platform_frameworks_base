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
 * limitations under the License.
 */

package com.android.server.power;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.os.PowerManager;

import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * Tests for {@link WakeLockLog}.
 */
public class WakeLockLogTest {

    @Test
    public void testAddTwoItems() {
        final int tagDatabaseSize = 128;
        final int logSize = 20;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy);

        when(injectorSpy.currentTimeMillis()).thenReturn(1000L);
        log.onWakeLockAcquired("TagPartial", 101,
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE);

        when(injectorSpy.currentTimeMillis()).thenReturn(1150L);
        log.onWakeLockAcquired("TagFull", 102,
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP);

        assertEquals("Wake Lock Log\n"
                + "  01-01 00:00:01.000 - 101 - ACQ TagPartial (partial,on-after-release)\n"
                + "  01-01 00:00:01.150 - 102 - ACQ TagFull (full,acq-causes-wake)\n"
                + "  -\n"
                + "  Events: 2, Time-Resets: 0\n"
                + "  Buffer, Bytes used: 6\n",
                dumpLog(log, false));
    }

    @Test
    public void testAddTwoItemsWithTimeReset() {
        final int tagDatabaseSize = 128;
        final int logSize = 20;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy);

        when(injectorSpy.currentTimeMillis()).thenReturn(1000L);
        log.onWakeLockAcquired("TagPartial", 101, PowerManager.PARTIAL_WAKE_LOCK);

        when(injectorSpy.currentTimeMillis()).thenReturn(1350L);
        log.onWakeLockAcquired("TagFull", 102, PowerManager.FULL_WAKE_LOCK);

        assertEquals("Wake Lock Log\n"
                + "  01-01 00:00:01.000 - 101 - ACQ TagPartial (partial)\n"
                + "  01-01 00:00:01.350 - 102 - ACQ TagFull (full)\n"
                + "  -\n"
                + "  Events: 2, Time-Resets: 1\n"
                + "  Buffer, Bytes used: 15\n",
                dumpLog(log, false));
    }

    @Test
    public void testAddTwoItemsWithTagOverwrite() {
        final int tagDatabaseSize = 2;
        final int logSize = 20;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy);

        when(injectorSpy.currentTimeMillis()).thenReturn(1000L);
        log.onWakeLockAcquired("TagPartial", 101, PowerManager.PARTIAL_WAKE_LOCK);

        when(injectorSpy.currentTimeMillis()).thenReturn(1150L);
        log.onWakeLockAcquired("TagFull", 102, PowerManager.FULL_WAKE_LOCK);

        assertEquals("Wake Lock Log\n"
                + "  01-01 00:00:01.000 - --- - ACQ UNKNOWN (partial)\n"
                + "  01-01 00:00:01.150 - 102 - ACQ TagFull (full)\n"
                + "  -\n"
                + "  Events: 2, Time-Resets: 0\n"
                + "  Buffer, Bytes used: 6\n",
                dumpLog(log, false));
    }

    @Test
    public void testAddFourItemsWithRingBufferOverflow() {
        final int tagDatabaseSize = 6;
        final int logSize = 10;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy);

        // This first item will get deleted when ring buffer loops around
        when(injectorSpy.currentTimeMillis()).thenReturn(1000L);
        log.onWakeLockAcquired("TagPartial", 101, PowerManager.PARTIAL_WAKE_LOCK);

        when(injectorSpy.currentTimeMillis()).thenReturn(1150L);
        log.onWakeLockAcquired("TagFull", 102, PowerManager.FULL_WAKE_LOCK);
        when(injectorSpy.currentTimeMillis()).thenReturn(1151L);
        log.onWakeLockAcquired("TagThree", 101, PowerManager.PARTIAL_WAKE_LOCK);
        when(injectorSpy.currentTimeMillis()).thenReturn(1152L);
        log.onWakeLockAcquired("TagFour", 101, PowerManager.PARTIAL_WAKE_LOCK);

        assertEquals("Wake Lock Log\n"
                + "  01-01 00:00:01.150 - 102 - ACQ TagFull (full)\n"
                + "  01-01 00:00:01.151 - 101 - ACQ TagThree (partial)\n"
                + "  01-01 00:00:01.152 - 101 - ACQ TagFour (partial)\n"
                + "  -\n"
                + "  Events: 3, Time-Resets: 0\n"
                + "  Buffer, Bytes used: 9\n",
                dumpLog(log, false));
    }

    @Test
    public void testAddItemWithBadTag() {
        final int tagDatabaseSize = 6;
        final int logSize = 10;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy);

        // Bad tag means it wont get written
        when(injectorSpy.currentTimeMillis()).thenReturn(1000L);
        log.onWakeLockAcquired(null /* tag */, 0 /* ownerUid */, PowerManager.PARTIAL_WAKE_LOCK);

        assertEquals("Wake Lock Log\n"
                + "  -\n"
                + "  Events: 0, Time-Resets: 0\n"
                + "  Buffer, Bytes used: 0\n",
                dumpLog(log, false));
    }

    @Test
    public void testAddItemWithReducedTagName() {
        final int tagDatabaseSize = 6;
        final int logSize = 10;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy);

        when(injectorSpy.currentTimeMillis()).thenReturn(1000L);
        log.onWakeLockAcquired("*job*/com.one.two.3hree/.one..Last", 101,
                PowerManager.PARTIAL_WAKE_LOCK);

        assertEquals("Wake Lock Log\n"
                + "  01-01 00:00:01.000 - 101 - ACQ *job*/c.o.t.3/.o..Last (partial)\n"
                + "  -\n"
                + "  Events: 1, Time-Resets: 0\n"
                + "  Buffer, Bytes used: 3\n",
                dumpLog(log, false));
    }

    @Test
    public void testAddAcquireAndReleaseWithRepeatTagName() {
        final int tagDatabaseSize = 6;
        final int logSize = 10;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy);

        when(injectorSpy.currentTimeMillis()).thenReturn(1000L);
        log.onWakeLockAcquired("HowdyTag", 101, PowerManager.PARTIAL_WAKE_LOCK);
        when(injectorSpy.currentTimeMillis()).thenReturn(1001L);
        log.onWakeLockReleased("HowdyTag", 101);

        assertEquals("Wake Lock Log\n"
                + "  01-01 00:00:01.000 - 101 - ACQ HowdyTag (partial)\n"
                + "  01-01 00:00:01.001 - 101 - REL HowdyTag\n"
                + "  -\n"
                + "  Events: 2, Time-Resets: 0\n"
                + "  Buffer, Bytes used: 5\n"
                + "  Tag Database: size(5), entries: 1, Bytes used: 80\n",
                dumpLog(log, true));
    }

    @Test
    public void testAddAcquireAndReleaseWithTimeTravel() {
        final int tagDatabaseSize = 6;
        final int logSize = 10;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy);

        when(injectorSpy.currentTimeMillis()).thenReturn(1100L);
        log.onWakeLockAcquired("HowdyTag", 101, PowerManager.PARTIAL_WAKE_LOCK);

        // New element goes back in time...should not be written to log.
        when(injectorSpy.currentTimeMillis()).thenReturn(1000L);
        log.onWakeLockReleased("HowdyTag", 101);

        assertEquals("Wake Lock Log\n"
                + "  01-01 00:00:01.100 - 101 - ACQ HowdyTag (partial)\n"
                + "  -\n"
                + "  Events: 1, Time-Resets: 0\n"
                + "  Buffer, Bytes used: 3\n",
                dumpLog(log, false));
    }

    @Test
    public void testAddSystemWakelock() {
        final int tagDatabaseSize = 6;
        final int logSize = 10;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy);

        when(injectorSpy.currentTimeMillis()).thenReturn(1000L);
        log.onWakeLockAcquired("TagPartial", 101,
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.SYSTEM_WAKELOCK);

        assertEquals("Wake Lock Log\n"
                        + "  01-01 00:00:01.000 - 101 - ACQ TagPartial (partial,system-wakelock)\n"
                        + "  -\n"
                        + "  Events: 1, Time-Resets: 0\n"
                        + "  Buffer, Bytes used: 3\n",
                dumpLog(log, false));
    }

    private String dumpLog(WakeLockLog log, boolean includeTagDb) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        log.dump(pw, includeTagDb);
        return sw.toString();
    }

    public class TestInjector extends WakeLockLog.Injector {
        private final int mTagDatabaseSize;
        private final int mLogSize;

        public TestInjector(int tagDatabaseSize, int logSize) {
            mTagDatabaseSize = tagDatabaseSize;
            mLogSize = logSize;
        }

        @Override
        public int getTagDatabaseSize() {
            return mTagDatabaseSize;
        }

        @Override
        public int getLogSize() {
            return mLogSize;
        }

        @Override
        public SimpleDateFormat getDateFormat() {
            SimpleDateFormat format = new SimpleDateFormat(super.getDateFormat().toPattern());
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format;
        }
    }
}
