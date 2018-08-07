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
 * limitations under the License.
 */
package android.metrics;

import android.metrics.MetricsReader.Event;
import android.support.test.filters.LargeTest;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import junit.framework.TestCase;

import java.util.Collection;

@LargeTest
public class MetricsReaderTest extends TestCase {
    private static final int FULL_N = 10;
    private static final int CHECKPOINTED_N = 4;
    private static final int PID = 1;
    private static final int UID = 2;

    class FakeLogReader extends MetricsReader.LogReader {
        MetricsReader.Event[] mEvents;
        private long mHorizonMs;

        public FakeLogReader() {
            mEvents = new MetricsReader.Event[FULL_N];
            for (int i = 0; i < FULL_N; i++) {
                mEvents[i] = new MetricsReader.Event(
                        1000L + i,
                        PID,
                        UID,
                        new LogMaker(i).serialize());
            }
        }

        @Override
        public void readEvents(int[] tags, long horizonMs, Collection<Event> events) {
            mHorizonMs = horizonMs;
            for (int i = 0; i < mEvents.length; i++) {
                events.add(mEvents[i]);
            }
        }

        @Override
        public void writeCheckpoint(int tag) {
            int i = FULL_N - CHECKPOINTED_N - 1;
            mEvents[i].setData(new LogMaker(MetricsEvent.METRICS_CHECKPOINT)
                    .setSubtype(tag)
                    .serialize());
        }
    }
    FakeLogReader mLogReader;
    MetricsReader mReader = new MetricsReader();

    public void setUp() {
        mLogReader = new FakeLogReader();
        mReader.setLogReader(mLogReader);
    }

    public void testNonBlockingRead() {
        mReader.read(0);
        assertEquals(0, mLogReader.mHorizonMs);
        for (int i = 0; i < FULL_N; i++) {
            assertTrue(mReader.hasNext());
            LogMaker log = mReader.next();
            assertEquals(i, log.getCategory());
        }
    }

    public void testReset() {
        mReader.read(0);
        while (mReader.hasNext()) {
            mReader.next();
        }
        mReader.reset();
        for (int i = 0; i < FULL_N; i++) {
            assertTrue(mReader.hasNext());
            LogMaker log = mReader.next();
            assertEquals(i, log.getCategory());
        }
    }

    public void testPidUid() {
        mReader.read(0);
        LogMaker log = mReader.next();
        assertEquals(PID, log.getProcessId());
        assertEquals(UID, log.getUid());
    }

    public void testBlockingRead_readResetsHorizon() {
        mReader.read(1000);
        assertEquals(1000, mLogReader.mHorizonMs);
    }

    public void testWriteCheckpoint() {
        mReader.checkpoint();
        mReader.read(0);
        int m = FULL_N - CHECKPOINTED_N;
        for (int i = m; i < FULL_N; i++) {
            assertTrue(mReader.hasNext());
            LogMaker log = mReader.next();
            assertEquals(i, log.getCategory());
        }
    }
}
