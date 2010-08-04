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

package android.os;

import android.os.PerformanceCollector.PerformanceResultsWriter;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

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

    @SmallTest
    public void testBeginSnapshotNoWriter() throws Exception {
        mPerfCollector.beginSnapshot("testBeginSnapshotNoWriter");

        assertTrue((Long)readPrivateField("mSnapshotCpuTime", mPerfCollector) > 0);
        assertTrue((Long)readPrivateField("mSnapshotExecTime", mPerfCollector) > 0);
        Bundle snapshot = (Bundle)readPrivateField("mPerfSnapshot", mPerfCollector);
        assertNotNull(snapshot);
        assertEquals(2, snapshot.size());
    }

    @MediumTest
    public void testEndSnapshotNoWriter() throws Exception {
        mPerfCollector.beginSnapshot("testEndSnapshotNoWriter");
        workForRandomLongPeriod();
        Bundle snapshot = mPerfCollector.endSnapshot();

        verifySnapshotBundle(snapshot);
    }

    @SmallTest
    public void testStartTimingNoWriter() throws Exception {
        mPerfCollector.startTiming("testStartTimingNoWriter");

        assertTrue((Long)readPrivateField("mCpuTime", mPerfCollector) > 0);
        assertTrue((Long)readPrivateField("mExecTime", mPerfCollector) > 0);
        Bundle measurement = (Bundle)readPrivateField("mPerfMeasurement", mPerfCollector);
        assertNotNull(measurement);
        verifyTimingBundle(measurement, new ArrayList<String>());
    }

    @SmallTest
    public void testAddIterationNoWriter() throws Exception {
        mPerfCollector.startTiming("testAddIterationNoWriter");
        workForRandomTinyPeriod();
        Bundle iteration = mPerfCollector.addIteration("timing1");

        verifyIterationBundle(iteration, "timing1");
    }

    @SmallTest
    public void testStopTimingNoWriter() throws Exception {
        mPerfCollector.startTiming("testStopTimingNoWriter");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("timing2");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("timing3");
        workForRandomShortPeriod();
        Bundle timing = mPerfCollector.stopTiming("timing4");

        ArrayList<String> labels = new ArrayList<String>();
        labels.add("timing2");
        labels.add("timing3");
        labels.add("timing4");
        verifyTimingBundle(timing, labels);
    }

    @SmallTest
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

    @MediumTest
    public void testEndSnapshot() throws Exception {
        MockPerformanceResultsWriter writer = new MockPerformanceResultsWriter();
        mPerfCollector.setPerformanceResultsWriter(writer);
        mPerfCollector.beginSnapshot("testEndSnapshot");
        workForRandomLongPeriod();
        Bundle snapshot1 = mPerfCollector.endSnapshot();
        Bundle snapshot2 = writer.snapshotResults;

        assertEqualsBundle(snapshot1, snapshot2);
        verifySnapshotBundle(snapshot1);
    }

    @SmallTest
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

    @SmallTest
    public void testAddIteration() throws Exception {
        mPerfCollector.startTiming("testAddIteration");
        workForRandomTinyPeriod();
        Bundle iteration = mPerfCollector.addIteration("timing5");

        verifyIterationBundle(iteration, "timing5");
    }

    @SmallTest
    public void testStopTiming() throws Exception {
        mPerfCollector.startTiming("testStopTiming");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("timing6");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("timing7");
        workForRandomShortPeriod();
        Bundle timing = mPerfCollector.stopTiming("timing8");

        ArrayList<String> labels = new ArrayList<String>();
        labels.add("timing6");
        labels.add("timing7");
        labels.add("timing8");
        verifyTimingBundle(timing, labels);
    }

    @SmallTest
    public void testAddMeasurementLong() throws Exception {
        MockPerformanceResultsWriter writer = new MockPerformanceResultsWriter();
        mPerfCollector.setPerformanceResultsWriter(writer);
        mPerfCollector.startTiming("testAddMeasurementLong");
        mPerfCollector.addMeasurement("testAddMeasurementLongZero", 0);
        mPerfCollector.addMeasurement("testAddMeasurementLongPos", 348573);
        mPerfCollector.addMeasurement("testAddMeasurementLongNeg", -19354);
        mPerfCollector.stopTiming("");

        assertEquals("testAddMeasurementLong", writer.timingLabel);
        Bundle results = writer.timingResults;
        assertEquals(4, results.size());
        assertTrue(results.containsKey("testAddMeasurementLongZero"));
        assertEquals(0, results.getLong("testAddMeasurementLongZero"));
        assertTrue(results.containsKey("testAddMeasurementLongPos"));
        assertEquals(348573, results.getLong("testAddMeasurementLongPos"));
        assertTrue(results.containsKey("testAddMeasurementLongNeg"));
        assertEquals(-19354, results.getLong("testAddMeasurementLongNeg"));
    }

    @SmallTest
    public void testAddMeasurementFloat() throws Exception {
        MockPerformanceResultsWriter writer = new MockPerformanceResultsWriter();
        mPerfCollector.setPerformanceResultsWriter(writer);
        mPerfCollector.startTiming("testAddMeasurementFloat");
        mPerfCollector.addMeasurement("testAddMeasurementFloatZero", 0.0f);
        mPerfCollector.addMeasurement("testAddMeasurementFloatPos", 348573.345f);
        mPerfCollector.addMeasurement("testAddMeasurementFloatNeg", -19354.093f);
        mPerfCollector.stopTiming("");

        assertEquals("testAddMeasurementFloat", writer.timingLabel);
        Bundle results = writer.timingResults;
        assertEquals(4, results.size());
        assertTrue(results.containsKey("testAddMeasurementFloatZero"));
        assertEquals(0.0f, results.getFloat("testAddMeasurementFloatZero"));
        assertTrue(results.containsKey("testAddMeasurementFloatPos"));
        assertEquals(348573.345f, results.getFloat("testAddMeasurementFloatPos"));
        assertTrue(results.containsKey("testAddMeasurementFloatNeg"));
        assertEquals(-19354.093f, results.getFloat("testAddMeasurementFloatNeg"));
    }

    @SmallTest
    public void testAddMeasurementString() throws Exception {
        MockPerformanceResultsWriter writer = new MockPerformanceResultsWriter();
        mPerfCollector.setPerformanceResultsWriter(writer);
        mPerfCollector.startTiming("testAddMeasurementString");
        mPerfCollector.addMeasurement("testAddMeasurementStringNull", null);
        mPerfCollector.addMeasurement("testAddMeasurementStringEmpty", "");
        mPerfCollector.addMeasurement("testAddMeasurementStringNonEmpty", "Hello World");
        mPerfCollector.stopTiming("");

        assertEquals("testAddMeasurementString", writer.timingLabel);
        Bundle results = writer.timingResults;
        assertEquals(4, results.size());
        assertTrue(results.containsKey("testAddMeasurementStringNull"));
        assertNull(results.getString("testAddMeasurementStringNull"));
        assertTrue(results.containsKey("testAddMeasurementStringEmpty"));
        assertEquals("", results.getString("testAddMeasurementStringEmpty"));
        assertTrue(results.containsKey("testAddMeasurementStringNonEmpty"));
        assertEquals("Hello World", results.getString("testAddMeasurementStringNonEmpty"));
    }

    @MediumTest
    public void testSimpleSequence() throws Exception {
        MockPerformanceResultsWriter writer = new MockPerformanceResultsWriter();
        mPerfCollector.setPerformanceResultsWriter(writer);
        mPerfCollector.beginSnapshot("testSimpleSequence");
        mPerfCollector.startTiming("testSimpleSequenceTiming");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration1");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration2");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration3");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration4");
        workForRandomShortPeriod();
        Bundle timing = mPerfCollector.stopTiming("iteration5");
        workForRandomLongPeriod();
        Bundle snapshot1 = mPerfCollector.endSnapshot();
        Bundle snapshot2 = writer.snapshotResults;

        assertEqualsBundle(snapshot1, snapshot2);
        verifySnapshotBundle(snapshot1);

        ArrayList<String> labels = new ArrayList<String>();
        labels.add("iteration1");
        labels.add("iteration2");
        labels.add("iteration3");
        labels.add("iteration4");
        labels.add("iteration5");
        verifyTimingBundle(timing, labels);
    }

    @MediumTest
    public void testLongSequence() throws Exception {
        MockPerformanceResultsWriter writer = new MockPerformanceResultsWriter();
        mPerfCollector.setPerformanceResultsWriter(writer);
        mPerfCollector.beginSnapshot("testLongSequence");
        mPerfCollector.startTiming("testLongSequenceTiming1");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration1");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration2");
        workForRandomShortPeriod();
        Bundle timing1 = mPerfCollector.stopTiming("iteration3");
        workForRandomLongPeriod();

        mPerfCollector.startTiming("testLongSequenceTiming2");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration4");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration5");
        workForRandomShortPeriod();
        Bundle timing2 = mPerfCollector.stopTiming("iteration6");
        workForRandomLongPeriod();

        mPerfCollector.startTiming("testLongSequenceTiming3");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration7");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration8");
        workForRandomShortPeriod();
        Bundle timing3 = mPerfCollector.stopTiming("iteration9");
        workForRandomLongPeriod();

        mPerfCollector.startTiming("testLongSequenceTiming4");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration10");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration11");
        workForRandomShortPeriod();
        Bundle timing4 = mPerfCollector.stopTiming("iteration12");
        workForRandomLongPeriod();

        mPerfCollector.startTiming("testLongSequenceTiming5");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration13");
        workForRandomTinyPeriod();
        mPerfCollector.addIteration("iteration14");
        workForRandomShortPeriod();
        Bundle timing5 = mPerfCollector.stopTiming("iteration15");
        workForRandomLongPeriod();
        Bundle snapshot1 = mPerfCollector.endSnapshot();
        Bundle snapshot2 = writer.snapshotResults;

        assertEqualsBundle(snapshot1, snapshot2);
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
    @MediumTest
    public void testOutOfOrderSequence() {
        MockPerformanceResultsWriter writer = new MockPerformanceResultsWriter();
        mPerfCollector.setPerformanceResultsWriter(writer);
        mPerfCollector.startTiming("testOutOfOrderSequenceTiming");
        workForRandomShortPeriod();
        mPerfCollector.beginSnapshot("testOutOfOrderSequenceSnapshot");
        workForRandomShortPeriod();
        Bundle timing1 = mPerfCollector.stopTiming("timing1");
        workForRandomShortPeriod();
        Bundle snapshot1 = mPerfCollector.endSnapshot();

        Bundle timing2 = writer.timingResults;
        Bundle snapshot2 = writer.snapshotResults;

        assertEqualsBundle(snapshot1, snapshot2);
        verifySnapshotBundle(snapshot1);

        assertEqualsBundle(timing1, timing2);
        ArrayList<String> labels = new ArrayList<String>();
        labels.add("timing1");
        verifyTimingBundle(timing1, labels);
    }

    private void workForRandomPeriod(int minDuration, int maxDuration) {
        Random random = new Random();
        int period = minDuration + random.nextInt(maxDuration - minDuration);
        long start = Process.getElapsedCpuTime();
        // Generate positive amount of work, so cpu time is measurable in
        // milliseconds
        while (Process.getElapsedCpuTime() - start < period) {
            for (int i = 0, temp = 0; i < 50; i++ ) {
                temp += i;
            }
        }
    }

    private void workForRandomTinyPeriod() {
        workForRandomPeriod(2, 5);
    }

    private void workForRandomShortPeriod() {
        workForRandomPeriod(10, 25);
    }

    private void workForRandomLongPeriod() {
        workForRandomPeriod(50, 100);
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

    private void assertEqualsBundle(Bundle b1, Bundle b2) {
        assertEquals(b1.keySet(), b2.keySet());
        for (String key : b1.keySet()) {
            assertEquals(b1.get(key), b2.get(key));
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
            snapshotResults.putAll(results);
        }

        public void writeStartTiming(String label) {
            timingLabel = label;
        }

        public void writeStopTiming(Bundle results) {
            timingResults.putAll(results);
        }

        public void writeMeasurement(String label, long value) {
            timingResults.putLong(label, value);
        }

        public void writeMeasurement(String label, float value) {
            timingResults.putFloat(label, value);
        }

        public void writeMeasurement(String label, String value) {
            timingResults.putString(label, value);
        }
    }
}
