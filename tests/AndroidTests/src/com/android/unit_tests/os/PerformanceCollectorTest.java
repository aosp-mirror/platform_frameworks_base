/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.unit_tests.os;

import android.os.Bundle;
import android.os.Parcelable;
import android.os.PerformanceCollector;
import android.os.PerformanceCollector.PerformanceResultsWriter;
import android.test.suitebuilder.annotation.LargeTest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Random;

import junit.framework.TestCase;

public class PerformanceCollectorTest extends TestCase {

    private PerformanceCollector mPerfCollector;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mPerfCollector = new PerformanceCollector();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mPerfCollector = null;
    }

    public void testBeginSnapshotNoWriter() throws Exception {
        mPerfCollector.beginSnapshot("testBeginSnapshotNoWriter");

        assertTrue((Long)readPrivateField("mSnapshotCpuTime", mPerfCollector) > 0);
        assertTrue((Long)readPrivateField("mSnapshotExecTime", mPerfCollector) > 0);
        Bundle snapshot = (Bundle)readPrivateField("mPerfSnapshot", mPerfCollector);
        assertNotNull(snapshot);
        assertEquals(2, snapshot.size());
    }

    @LargeTest
    public void testEndSnapshotNoWriter() throws Exception {
        mPerfCollector.beginSnapshot("testEndSnapshotNoWriter");
        sleepForRandomLongPeriod();
        Bundle snapshot = mPerfCollector.endSnapshot();

        verifySnapshotBundle(snapshot);
    }

    public void testStartTimingNoWriter() throws Exception {
        mPerfCollector.startTiming("testStartTimingNoWriter");

        assertTrue((Long)readPrivateField("mCpuTime", mPerfCollector) > 0);
        assertTrue((Long)readPrivateField("mExecTime", mPerfCollector) > 0);
        Bundle measurement = (Bundle)readPrivateField("mPerfMeasurement", mPerfCollector);
        assertNotNull(measurement);
        verifyTimingBundle(measurement, new ArrayList<String>());
    }

    public void testAddIterationNoWriter() throws Exception {
        mPerfCollector.startTiming("testAddIterationNoWriter");
        sleepForRandomTinyPeriod();
        Bundle iteration = mPerfCollector.addIteration("timing1");

        verifyIterationBundle(iteration, "timing1");
    }

    public void testStopTimingNoWriter() throws Exception {
        mPerfCollector.startTiming("testStopTimingNoWriter");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("timing2");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("timing3");
        sleepForRandomShortPeriod();
        Bundle timing = mPerfCollector.stopTiming("timing4");

        ArrayList<String> labels = new ArrayList<String>();
        labels.add("timing2");
        labels.add("timing3");
        labels.add("timing4");
        verifyTimingBundle(timing, labels);
    }

    public void testBeginSnapshot() throws Exception {
        MockPerformanceResultsWriter writer = new MockPerformanceResultsWriter();
        mPerfCollector.setPerformanceResultsWriter(writer);
        mPerfCollector.beginSnapshot("testBeginSnapshot");

        assertEquals("testBeginSnapshot", writer.snapshotLabel);
        assertTrue((Long)readPrivateField("mSnapshotCpuTime", mPerfCollector) > 0);
        assertTrue((Long)readPrivateField("mSnapshotExecTime", mPerfCollector) > 0);
        Bundle snapshot = (Bundle)readPrivateField("mPerfSnapshot", mPerfCollector);
        assertNotNull(snapshot);
        assertEquals(2, snapshot.size());
    }

    @LargeTest
    public void testEndSnapshot() throws Exception {
        MockPerformanceResultsWriter writer = new MockPerformanceResultsWriter();
        mPerfCollector.setPerformanceResultsWriter(writer);
        mPerfCollector.beginSnapshot("testEndSnapshot");
        sleepForRandomLongPeriod();
        Bundle snapshot1 = mPerfCollector.endSnapshot();
        Bundle snapshot2 = writer.snapshotResults;

        assertTrue(snapshot1.equals(snapshot2));
        verifySnapshotBundle(snapshot1);
    }

    public void testStartTiming() throws Exception {
        MockPerformanceResultsWriter writer = new MockPerformanceResultsWriter();
        mPerfCollector.setPerformanceResultsWriter(writer);
        mPerfCollector.startTiming("testStartTiming");

        assertEquals("testStartTiming", writer.timingLabel);
        assertTrue((Long)readPrivateField("mCpuTime", mPerfCollector) > 0);
        assertTrue((Long)readPrivateField("mExecTime", mPerfCollector) > 0);
        Bundle measurement = (Bundle)readPrivateField("mPerfMeasurement", mPerfCollector);
        assertNotNull(measurement);
        verifyTimingBundle(measurement, new ArrayList<String>());
    }

    public void testAddIteration() throws Exception {
        mPerfCollector.startTiming("testAddIteration");
        sleepForRandomTinyPeriod();
        Bundle iteration = mPerfCollector.addIteration("timing5");

        verifyIterationBundle(iteration, "timing5");
    }

    public void testStopTiming() throws Exception {
        mPerfCollector.startTiming("testStopTiming");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("timing6");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("timing7");
        sleepForRandomShortPeriod();
        Bundle timing = mPerfCollector.stopTiming("timing8");

        ArrayList<String> labels = new ArrayList<String>();
        labels.add("timing6");
        labels.add("timing7");
        labels.add("timing8");
        verifyTimingBundle(timing, labels);
    }

    @LargeTest
    public void testSimpleSequence() throws Exception {
        MockPerformanceResultsWriter writer = new MockPerformanceResultsWriter();
        mPerfCollector.setPerformanceResultsWriter(writer);
        mPerfCollector.beginSnapshot("testSimpleSequence");
        mPerfCollector.startTiming("testSimpleSequenceTiming");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration1");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration2");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration3");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration4");
        sleepForRandomShortPeriod();
        Bundle timing = mPerfCollector.stopTiming("iteration5");
        sleepForRandomLongPeriod();
        Bundle snapshot1 = mPerfCollector.endSnapshot();
        Bundle snapshot2 = writer.snapshotResults;

        assertTrue(snapshot1.equals(snapshot2));
        verifySnapshotBundle(snapshot1);

        ArrayList<String> labels = new ArrayList<String>();
        labels.add("iteration1");
        labels.add("iteration2");
        labels.add("iteration3");
        labels.add("iteration4");
        labels.add("iteration5");
        verifyTimingBundle(timing, labels);
    }

    @LargeTest
    public void testLongSequence() throws Exception {
        MockPerformanceResultsWriter writer = new MockPerformanceResultsWriter();
        mPerfCollector.setPerformanceResultsWriter(writer);
        mPerfCollector.beginSnapshot("testLongSequence");
        mPerfCollector.startTiming("testLongSequenceTiming1");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration1");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration2");
        sleepForRandomShortPeriod();
        Bundle timing1 = mPerfCollector.stopTiming("iteration3");
        sleepForRandomLongPeriod();

        mPerfCollector.startTiming("testLongSequenceTiming2");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration4");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration5");
        sleepForRandomShortPeriod();
        Bundle timing2 = mPerfCollector.stopTiming("iteration6");
        sleepForRandomLongPeriod();

        mPerfCollector.startTiming("testLongSequenceTiming3");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration7");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration8");
        sleepForRandomShortPeriod();
        Bundle timing3 = mPerfCollector.stopTiming("iteration9");
        sleepForRandomLongPeriod();

        mPerfCollector.startTiming("testLongSequenceTiming4");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration10");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration11");
        sleepForRandomShortPeriod();
        Bundle timing4 = mPerfCollector.stopTiming("iteration12");
        sleepForRandomLongPeriod();

        mPerfCollector.startTiming("testLongSequenceTiming5");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration13");
        sleepForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration14");
        sleepForRandomShortPeriod();
        Bundle timing5 = mPerfCollector.stopTiming("iteration15");
        sleepForRandomLongPeriod();
        Bundle snapshot1 = mPerfCollector.endSnapshot();
        Bundle snapshot2 = writer.snapshotResults;

        assertTrue(snapshot1.equals(snapshot2));
        verifySnapshotBundle(snapshot1);

        ArrayList<String> labels1 = new ArrayList<String>();
        labels1.add("iteration1");
        labels1.add("iteration2");
        labels1.add("iteration3");
        verifyTimingBundle(timing1, labels1);
        ArrayList<String> labels2 = new ArrayList<String>();
        labels2.add("iteration4");
        labels2.add("iteration5");
        labels2.add("iteration6");
        verifyTimingBundle(timing2, labels2);
        ArrayList<String> labels3 = new ArrayList<String>();
        labels3.add("iteration7");
        labels3.add("iteration8");
        labels3.add("iteration9");
        verifyTimingBundle(timing3, labels3);
        ArrayList<String> labels4 = new ArrayList<String>();
        labels4.add("iteration10");
        labels4.add("iteration11");
        labels4.add("iteration12");
        verifyTimingBundle(timing4, labels4);
        ArrayList<String> labels5 = new ArrayList<String>();
        labels5.add("iteration13");
        labels5.add("iteration14");
        labels5.add("iteration15");
        verifyTimingBundle(timing5, labels5);
    }

    /*
     * Verify that snapshotting and timing do not interfere w/ each other,
     * by staggering calls to snapshot and timing functions.
     */
    @LargeTest
    public void testOutOfOrderSequence() {
        MockPerformanceResultsWriter writer = new MockPerformanceResultsWriter();
        mPerfCollector.setPerformanceResultsWriter(writer);
        mPerfCollector.startTiming("testOutOfOrderSequenceTiming");
        sleepForRandomShortPeriod();
        mPerfCollector.beginSnapshot("testOutOfOrderSequenceSnapshot");
        sleepForRandomShortPeriod();
        Bundle timing1 = mPerfCollector.stopTiming("timing1");
        sleepForRandomShortPeriod();
        Bundle snapshot1 = mPerfCollector.endSnapshot();

        Bundle timing2 = writer.timingResults;
        Bundle snapshot2 = writer.snapshotResults;

        assertTrue(snapshot1.equals(snapshot2));
        verifySnapshotBundle(snapshot1);

        assertTrue(timing1.equals(timing2));
        ArrayList<String> labels = new ArrayList<String>();
        labels.add("timing1");
        verifyTimingBundle(timing1, labels);
    }

    private void sleepForRandomPeriod(int minDuration, int maxDuration) {
        Random random = new Random();
        int period = minDuration + random.nextInt(maxDuration - minDuration);
        int slept = 0;
        // Generate random positive amount of work, so cpu time is measurable in
        // milliseconds
        while (slept < period) {
            int step = random.nextInt(minDuration/5);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e ) {
                // eat the exception
            }
            slept += step;
        }
    }

    private void sleepForRandomTinyPeriod() {
        sleepForRandomPeriod(25, 50);
    }

    private void sleepForRandomShortPeriod() {
        sleepForRandomPeriod(100, 250);
    }

    private void sleepForRandomLongPeriod() {
        sleepForRandomPeriod(500, 1000);
    }

    private void verifySnapshotBundle(Bundle snapshot) {
        assertTrue("At least 26 metrics collected", 26 <= snapshot.size());

        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_CPU_TIME));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_CPU_TIME) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_EXECUTION_TIME));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_EXECUTION_TIME) > 0);

        assertTrue(snapshot.containsKey(
                PerformanceCollector.METRIC_KEY_PRE_RECEIVED_TRANSACTIONS));
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_PRE_SENT_TRANSACTIONS));
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_RECEIVED_TRANSACTIONS));
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_SENT_TRANSACTIONS));
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_GC_INVOCATION_COUNT));

        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_JAVA_ALLOCATED));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_JAVA_ALLOCATED) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_JAVA_FREE));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_JAVA_FREE) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_JAVA_PRIVATE_DIRTY));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_JAVA_PRIVATE_DIRTY) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_JAVA_PSS));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_JAVA_PSS) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_JAVA_SHARED_DIRTY));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_JAVA_SHARED_DIRTY) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_JAVA_SIZE));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_JAVA_SIZE) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_NATIVE_ALLOCATED));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_NATIVE_ALLOCATED) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_NATIVE_FREE));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_NATIVE_FREE) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_NATIVE_PRIVATE_DIRTY));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_NATIVE_PRIVATE_DIRTY) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_NATIVE_PSS));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_NATIVE_PSS) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_NATIVE_SHARED_DIRTY));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_NATIVE_SHARED_DIRTY) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_NATIVE_SIZE));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_NATIVE_SIZE) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_GLOBAL_ALLOC_COUNT));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_GLOBAL_ALLOC_COUNT) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_GLOBAL_ALLOC_SIZE));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_GLOBAL_ALLOC_SIZE) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_GLOBAL_FREED_COUNT));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_GLOBAL_FREED_COUNT) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_GLOBAL_FREED_SIZE));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_GLOBAL_FREED_SIZE) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_OTHER_PRIVATE_DIRTY));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_OTHER_PRIVATE_DIRTY) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_OTHER_PSS));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_OTHER_PSS) > 0);
        assertTrue(snapshot.containsKey(PerformanceCollector.METRIC_KEY_OTHER_SHARED_DIRTY));
        assertTrue(snapshot.getLong(PerformanceCollector.METRIC_KEY_OTHER_SHARED_DIRTY) > 0);
    }

    private void verifyIterationBundle(Bundle iteration, String label) {
        assertEquals(3, iteration.size());
        assertTrue(iteration.containsKey(PerformanceCollector.METRIC_KEY_LABEL));
        assertEquals(label, iteration.getString(PerformanceCollector.METRIC_KEY_LABEL));
        assertTrue(iteration.containsKey(PerformanceCollector.METRIC_KEY_CPU_TIME));
        assertTrue(iteration.getLong(PerformanceCollector.METRIC_KEY_CPU_TIME) > 0);
        assertTrue(iteration.containsKey(PerformanceCollector.METRIC_KEY_EXECUTION_TIME));
        assertTrue(iteration.getLong(PerformanceCollector.METRIC_KEY_EXECUTION_TIME) > 0);
    }

    private void verifyTimingBundle(Bundle timing, ArrayList<String> labels) {
        assertEquals(1, timing.size());
        assertTrue(timing.containsKey(PerformanceCollector.METRIC_KEY_ITERATIONS));
        ArrayList<Parcelable> iterations = timing.getParcelableArrayList(
                PerformanceCollector.METRIC_KEY_ITERATIONS);
        assertNotNull(iterations);
        assertEquals(labels.size(), iterations.size());
        for (int i = 0; i < labels.size(); i ++) {
            Bundle iteration = (Bundle)iterations.get(i);
            verifyIterationBundle(iteration, labels.get(i));
        }
    }

    private Object readPrivateField(String fieldName, Object object) throws Exception {
        Field f = object.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(object);
    }

    private class MockPerformanceResultsWriter implements PerformanceResultsWriter {

        public String snapshotLabel;
        public Bundle snapshotResults = new Bundle();
        public String timingLabel;
        public Bundle timingResults = new Bundle();

        public void writeBeginSnapshot(String label) {
            snapshotLabel = label;
        }

        public void writeEndSnapshot(Bundle results) {
            snapshotResults = results;
        }

        public void writeStartTiming(String label) {
            timingLabel = label;
        }

        public void writeStopTiming(Bundle results) {
            timingResults = results;
        }
    }
}
