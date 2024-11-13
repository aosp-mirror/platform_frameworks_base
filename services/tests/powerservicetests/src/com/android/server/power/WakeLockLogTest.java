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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.Process;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * Tests for {@link WakeLockLog}.
 */
public class WakeLockLogTest {

    @Mock
    private Context mContext;

    @Mock
    private PackageManager mPackageManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);

        when(mPackageManager.getPackagesForUid(101)).thenReturn(new String[]{ "some.package1" });
        when(mPackageManager.getPackagesForUid(102)).thenReturn(new String[]{ "some.package2" });
        when(mPackageManager.getPackagesForUid(Process.SYSTEM_UID))
                .thenReturn(new String[]{ "some.package3" });
    }

    @Test
    public void testAddTwoItems_withNoEventTimeSupplied() {
        final int tagDatabaseSize = 128;
        final int logSize = 20;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy, mContext);
        when(injectorSpy.currentTimeMillis()).thenReturn(1000L);
        log.onWakeLockAcquired("TagPartial", 101,
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, -1);

        when(injectorSpy.currentTimeMillis()).thenReturn(1150L);
        log.onWakeLockAcquired("TagFull", 102,
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, -1);

        when(injectorSpy.currentTimeMillis()).thenReturn(1250L);
        log.onWakeLockAcquired("TagSystem", 1000,
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, -1);

        assertEquals("Wake Lock Log\n"
                        + "  01-01 00:00:01.000 - 101 (some.package1) - ACQ TagPartial "
                        + "(partial,on-after-release)\n"
                        + "  01-01 00:00:01.150 - 102 (some.package2) - ACQ TagFull "
                        + "(full,acq-causes-wake)\n"
                        + "  01-01 00:00:01.250 - 1000 (" + WakeLockLog.SYSTEM_PACKAGE_NAME + ")"
                        + " - ACQ TagSystem (full,acq-causes-wake)\n"
                        + "  -\n"
                        + "  Events: 3, Time-Resets: 0\n"
                        + "  Buffer, Bytes used: 9\n",
                dumpLog(log, false));
    }

    @Test
    public void testAddTwoItems() {
        final int tagDatabaseSize = 128;
        final int logSize = 20;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy, mContext);

        log.onWakeLockAcquired("TagPartial", 101,
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, 1000L);

        log.onWakeLockAcquired("TagFull", 102,
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 1150L);

        assertEquals("Wake Lock Log\n"
                + "  01-01 00:00:01.000 - 101 (some.package1) - ACQ TagPartial "
                        + "(partial,on-after-release)\n"
                + "  01-01 00:00:01.150 - 102 (some.package2) - ACQ TagFull "
                        + "(full,acq-causes-wake)\n"
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
        WakeLockLog log = new WakeLockLog(injectorSpy, mContext);

        log.onWakeLockAcquired("TagPartial", 101, PowerManager.PARTIAL_WAKE_LOCK, 1000L);

        log.onWakeLockAcquired("TagFull", 102, PowerManager.FULL_WAKE_LOCK, 1350L);

        assertEquals("Wake Lock Log\n"
                + "  01-01 00:00:01.000 - 101 (some.package1) - ACQ TagPartial (partial)\n"
                + "  01-01 00:00:01.350 - 102 (some.package2) - ACQ TagFull (full)\n"
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
        WakeLockLog log = new WakeLockLog(injectorSpy, mContext);

        log.onWakeLockAcquired("TagPartial", 101, PowerManager.PARTIAL_WAKE_LOCK, 1000L);

        log.onWakeLockAcquired("TagFull", 102, PowerManager.FULL_WAKE_LOCK, 1150L);

        assertEquals("Wake Lock Log\n"
                + "  01-01 00:00:01.000 - --- - ACQ UNKNOWN (partial)\n"
                + "  01-01 00:00:01.150 - 102 (some.package2) - ACQ TagFull (full)\n"
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
        WakeLockLog log = new WakeLockLog(injectorSpy, mContext);

        // Wake lock 1 acquired - log size = 3
        log.onWakeLockAcquired("TagPartial", 101, PowerManager.PARTIAL_WAKE_LOCK, 1000L);

        // Wake lock 2 acquired - log size = 3 + 3 = 6
        log.onWakeLockAcquired("TagFull", 102, PowerManager.FULL_WAKE_LOCK, 1150L);

        // Wake lock 3 acquired - log size = 6 + 3 = 9
        log.onWakeLockAcquired("TagThree", 101, PowerManager.PARTIAL_WAKE_LOCK, 1151L);

        // We need more space - wake lock 1 acquisition is removed from the log and saved in the
        // list. Log size = 9 - 3 + 2 = 8
        log.onWakeLockReleased("TagThree", 101, 1152L);

        // We need more space - wake lock 2 acquisition is removed from the log and saved in the
        // list. Log size = 8 - 3 + 2 = 7
        log.onWakeLockReleased("TagPartial", 101, 1153L);

        // We need more space - wake lock 3 acquisition is removed from the log and saved in the
        // list. Log size = 7 - 3 + 3 = 7
        log.onWakeLockAcquired("TagFour", 101, PowerManager.PARTIAL_WAKE_LOCK, 1154L);

        // We need more space - wake lock 3 release is removed from the log and wake lock 3
        // acquisition is removed from the list. Log size = 7 - 2 + 3 = 8
        log.onWakeLockAcquired("TagFive", 101, PowerManager.PARTIAL_WAKE_LOCK, 1155L);

        // We need more space - wake lock 1 release is removed from the log and wake lock 1
        // acquisition is removed from the list. Log size = 8 - 2 + 2 = 8
        log.onWakeLockReleased("TagFull", 102, 1156L);

        // Wake lock 2 acquisition is still printed because its release have not rolled off the log
        // yet.
        assertEquals("Wake Lock Log\n"
                + "  01-01 00:00:01.150 - 102 (some.package2) - ACQ TagFull (full)\n"
                + "  01-01 00:00:01.154 - 101 (some.package1) - ACQ TagFour (partial)\n"
                + "  01-01 00:00:01.155 - 101 (some.package1) - ACQ TagFive (partial)\n"
                + "  01-01 00:00:01.156 - 102 (some.package2) - REL TagFull\n"
                + "  -\n"
                + "  Events: 4, Time-Resets: 0\n"
                + "  Buffer, Bytes used: 8\n",
                dumpLog(log, false));
    }

    @Test
    public void testAddItemWithBadTag() {
        final int tagDatabaseSize = 6;
        final int logSize = 10;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy, mContext);

        // Bad tag means it wont get written
        log.onWakeLockAcquired(
                null /* tag */, 0 /* ownerUid */, PowerManager.PARTIAL_WAKE_LOCK, 1000L);

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
        WakeLockLog log = new WakeLockLog(injectorSpy, mContext);

        log.onWakeLockAcquired("*job*/com.one.two.3hree/.one..Last", 101,
                PowerManager.PARTIAL_WAKE_LOCK, 1000L);

        assertEquals("Wake Lock Log\n"
                + "  01-01 00:00:01.000 - 101 (some.package1) - ACQ "
                        + "*job*/c.o.t.3/.o..Last (partial)\n"
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
        WakeLockLog log = new WakeLockLog(injectorSpy, mContext);

        log.onWakeLockAcquired("HowdyTag", 101, PowerManager.PARTIAL_WAKE_LOCK, 1000L);
        log.onWakeLockReleased("HowdyTag", 101, 1001L);

        assertEquals("Wake Lock Log\n"
                + "  01-01 00:00:01.000 - 101 (some.package1) - ACQ HowdyTag (partial)\n"
                + "  01-01 00:00:01.001 - 101 (some.package1) - REL HowdyTag\n"
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
        WakeLockLog log = new WakeLockLog(injectorSpy, mContext);

        log.onWakeLockAcquired("HowdyTag", 101, PowerManager.PARTIAL_WAKE_LOCK, 1100L);

        // New element goes back in time...should not be written to log.
        log.onWakeLockReleased("HowdyTag", 101, 1000L);

        assertEquals("Wake Lock Log\n"
                + "  01-01 00:00:01.100 - 101 (some.package1) - ACQ HowdyTag (partial)\n"
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
        WakeLockLog log = new WakeLockLog(injectorSpy, mContext);

        log.onWakeLockAcquired("TagPartial", 101,
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.SYSTEM_WAKELOCK, 1000L);

        assertEquals("Wake Lock Log\n"
                        + "  01-01 00:00:01.000 - 101 (some.package1) - ACQ TagPartial "
                                + "(partial,system-wakelock)\n"
                        + "  -\n"
                        + "  Events: 1, Time-Resets: 0\n"
                        + "  Buffer, Bytes used: 3\n",
                dumpLog(log, false));
    }

    @Test
    public void testAddItemWithNoPackageName() {
        final int tagDatabaseSize = 128;
        final int logSize = 20;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy, mContext);

        when(mPackageManager.getPackagesForUid(101)).thenReturn(null);
        log.onWakeLockAcquired("TagPartial", 101,
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, 1000L);

        assertEquals("Wake Lock Log\n"
                        + "  01-01 00:00:01.000 - 101 - ACQ TagPartial "
                                + "(partial,on-after-release)\n"
                        + "  -\n"
                        + "  Events: 1, Time-Resets: 0\n"
                        + "  Buffer, Bytes used: 3\n",
                dumpLog(log, false));
    }

    @Test
    public void testAddItemWithMultiplePackageNames() {
        final int tagDatabaseSize = 128;
        final int logSize = 20;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy, mContext);

        when(mPackageManager.getPackagesForUid(101)).thenReturn(
                new String[]{ "some.package1", "some.package2", "some.package3" });

        log.onWakeLockAcquired("TagPartial", 101,
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, 1000L);

        assertEquals("Wake Lock Log\n"
                        + "  01-01 00:00:01.000 - 101 (some.package1,...) - ACQ TagPartial "
                                + "(partial,on-after-release)\n"
                        + "  -\n"
                        + "  Events: 1, Time-Resets: 0\n"
                        + "  Buffer, Bytes used: 3\n",
                dumpLog(log, false));
    }

    @Test
    public void testAddItemsWithRepeatOwnerUid_UsesCache() {
        final int tagDatabaseSize = 128;
        final int logSize = 20;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy, mContext);

        log.onWakeLockAcquired("TagPartial", 101,
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, 1000L);

        log.onWakeLockAcquired("TagFull", 101,
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 1150L);

        log.onWakeLockAcquired("TagFull2", 101,
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 1151L);

        assertEquals("Wake Lock Log\n"
                        + "  01-01 00:00:01.000 - 101 (some.package1) - ACQ TagPartial "
                        + "(partial,on-after-release)\n"
                        + "  01-01 00:00:01.150 - 101 (some.package1) - ACQ TagFull "
                        + "(full,acq-causes-wake)\n"
                        + "  01-01 00:00:01.151 - 101 (some.package1) - ACQ TagFull2 "
                        + "(full,acq-causes-wake)\n"
                        + "  -\n"
                        + "  Events: 3, Time-Resets: 0\n"
                        + "  Buffer, Bytes used: 9\n",
                dumpLog(log, false));

        verify(mPackageManager, times(1)).getPackagesForUid(101);
    }

    @Test
    public void testAddItemsWithRepeatOwnerUid_SavedAcquisitions_UsesCache() {
        final int tagDatabaseSize = 128;
        final int logSize = 10;
        TestInjector injectorSpy = spy(new TestInjector(tagDatabaseSize, logSize));
        WakeLockLog log = new WakeLockLog(injectorSpy, mContext);

        log.onWakeLockAcquired("TagPartial", 101,
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, 1000L);

        log.onWakeLockAcquired("TagFull", 101,
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 1150L);

        log.onWakeLockAcquired("TagFull2", 101,
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 1151L);

        log.onWakeLockAcquired("TagFull3", 101,
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 1152L);

        log.onWakeLockAcquired("TagFull4", 101,
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 1153L);

        log.onWakeLockAcquired("TagFull5", 101,
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, 1154L);

        // The first 3 events have been removed from the log and they exist in the saved
        // acquisitions list. They should also use the cache when fetching the package names.
        assertEquals("Wake Lock Log\n"
                        + "  01-01 00:00:01.000 - 101 (some.package1) - ACQ TagPartial "
                        + "(partial,on-after-release)\n"
                        + "  01-01 00:00:01.150 - 101 (some.package1) - ACQ TagFull "
                        + "(full,acq-causes-wake)\n"
                        + "  01-01 00:00:01.151 - 101 (some.package1) - ACQ TagFull2 "
                        + "(full,acq-causes-wake)\n"
                        + "  01-01 00:00:01.152 - 101 (some.package1) - ACQ TagFull3 "
                        + "(full,acq-causes-wake)\n"
                        + "  01-01 00:00:01.153 - 101 (some.package1) - ACQ TagFull4 "
                        + "(full,acq-causes-wake)\n"
                        + "  01-01 00:00:01.154 - 101 (some.package1) - ACQ TagFull5 "
                        + "(full,acq-causes-wake)\n"
                        + "  -\n"
                        + "  Events: 6, Time-Resets: 0\n"
                        + "  Buffer, Bytes used: 9\n",
                dumpLog(log, false));

        verify(mPackageManager, times(1)).getPackagesForUid(101);
    }

    private String dumpLog(WakeLockLog log, boolean includeTagDb) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        log.dump(pw, includeTagDb);
        return sw.toString();
    }

    public static class TestInjector extends WakeLockLog.Injector {
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
