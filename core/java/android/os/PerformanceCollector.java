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


import android.compat.annotation.UnsupportedAppUsage;

import java.util.ArrayList;

/**
 * Collects performance data between two function calls in Bundle objects and
 * outputs the results using writer of type {@link PerformanceResultsWriter}.
 * <p>
 * {@link #beginSnapshot(String)} and {@link #endSnapshot()} functions collect
 * memory usage information and measure runtime between calls to begin and end.
 * These functions logically wrap around an entire test, and should be called
 * with name of test as the label, e.g. EmailPerformanceTest.
 * <p>
 * {@link #startTiming(String)} and {@link #stopTiming(String)} functions
 * measure runtime between calls to start and stop. These functions logically
 * wrap around a single test case or a small block of code, and should be called
 * with the name of test case as the label, e.g. testSimpleSendMailSequence.
 * <p>
 * {@link #addIteration(String)} inserts intermediate measurement point which
 * can be labeled with a String, e.g. Launch email app, compose, send, etc.
 * <p>
 * Snapshot and timing functions do not interfere with each other, and thus can
 * be called in any order. The intended structure is to wrap begin/endSnapshot
 * around calls to start/stopTiming, for example:
 * <p>
 * <code>beginSnapshot("EmailPerformanceTest");
 * startTiming("testSimpleSendSequence");
 * addIteration("Launch email app");
 * addIteration("Compose");
 * stopTiming("Send");
 * startTiming("testComplexSendSequence");
 * stopTiming("");
 * startTiming("testAddLabel");
 * stopTiming("");
 * endSnapshot();</code>
 * <p>
 * Structure of results output is up to implementor of
 * {@link PerformanceResultsWriter }.
 *
 * {@hide} Pending approval for public API.
 */
public class PerformanceCollector {

    /**
     * Interface for reporting performance data.
     */
    public interface PerformanceResultsWriter {

        /**
         * Callback invoked as first action in
         * PerformanceCollector#beginSnapshot(String) for reporting the start of
         * a performance snapshot.
         *
         * @param label description of code block between beginSnapshot and
         *              PerformanceCollector#endSnapshot()
         * @see PerformanceCollector#beginSnapshot(String)
         */
        public void writeBeginSnapshot(String label);

        /**
         * Callback invoked as last action in PerformanceCollector#endSnapshot()
         * for reporting performance data collected in the snapshot.
         *
         * @param results memory and runtime metrics stored as key/value pairs,
         *        in the same structure as returned by
         *        PerformanceCollector#endSnapshot()
         * @see PerformanceCollector#endSnapshot()
         */
        public void writeEndSnapshot(Bundle results);

        /**
         * Callback invoked as first action in
         * PerformanceCollector#startTiming(String) for reporting the start of
         * a timing measurement.
         *
         * @param label description of code block between startTiming and
         *              PerformanceCollector#stopTiming(String)
         * @see PerformanceCollector#startTiming(String)
         */
        public void writeStartTiming(String label);

        /**
         * Callback invoked as last action in
         * {@link PerformanceCollector#stopTiming(String)} for reporting the
         * sequence of timings measured.
         *
         * @param results runtime metrics of code block between calls to
         *                startTiming and stopTiming, in the same structure as
         *                returned by PerformanceCollector#stopTiming(String)
         * @see PerformanceCollector#stopTiming(String)
         */
        public void writeStopTiming(Bundle results);

        /**
         * Callback invoked as last action in
         * {@link PerformanceCollector#addMeasurement(String, long)} for
         * reporting an integer type measurement.
         *
         * @param label short description of the metric that was measured
         * @param value long value of the measurement
         */
        public void writeMeasurement(String label, long value);

        /**
         * Callback invoked as last action in
         * {@link PerformanceCollector#addMeasurement(String, float)} for
         * reporting a float type measurement.
         *
         * @param label short description of the metric that was measured
         * @param value float value of the measurement
         */
        public void writeMeasurement(String label, float value);

        /**
         * Callback invoked as last action in
         * {@link PerformanceCollector#addMeasurement(String, String)} for
         * reporting a string field.
         *
         * @param label short description of the metric that was measured
         * @param value string summary of the measurement
         */
        public void writeMeasurement(String label, String value);
    }

    /**
     * In a results Bundle, this key references a List of iteration Bundles.
     */
    public static final String METRIC_KEY_ITERATIONS = "iterations";
    /**
     * In an iteration Bundle, this key describes the iteration.
     */
    public static final String METRIC_KEY_LABEL = "label";
    /**
     * In a results Bundle, this key reports the cpu time of the code block
     * under measurement.
     */
    public static final String METRIC_KEY_CPU_TIME = "cpu_time";
    /**
     * In a results Bundle, this key reports the execution time of the code
     * block under measurement.
     */
    public static final String METRIC_KEY_EXECUTION_TIME = "execution_time";
    /**
     * In a snapshot Bundle, this key reports the number of received
     * transactions from the binder driver before collection started.
     */
    public static final String METRIC_KEY_PRE_RECEIVED_TRANSACTIONS = "pre_received_transactions";
    /**
     * In a snapshot Bundle, this key reports the number of transactions sent by
     * the running program before collection started.
     */
    public static final String METRIC_KEY_PRE_SENT_TRANSACTIONS = "pre_sent_transactions";
    /**
     * In a snapshot Bundle, this key reports the number of received
     * transactions from the binder driver.
     */
    public static final String METRIC_KEY_RECEIVED_TRANSACTIONS = "received_transactions";
    /**
     * In a snapshot Bundle, this key reports the number of transactions sent by
     * the running program.
     */
    public static final String METRIC_KEY_SENT_TRANSACTIONS = "sent_transactions";
    /**
     * In a snapshot Bundle, this key reports the number of garbage collection
     * invocations.
     */
    public static final String METRIC_KEY_GC_INVOCATION_COUNT = "gc_invocation_count";
    /**
     * In a snapshot Bundle, this key reports the amount of allocated memory
     * used by the running program.
     */
    public static final String METRIC_KEY_JAVA_ALLOCATED = "java_allocated";
    /**
     * In a snapshot Bundle, this key reports the amount of free memory
     * available to the running program.
     */
    public static final String METRIC_KEY_JAVA_FREE = "java_free";
    /**
     * In a snapshot Bundle, this key reports the number of private dirty pages
     * used by dalvik.
     */
    public static final String METRIC_KEY_JAVA_PRIVATE_DIRTY = "java_private_dirty";
    /**
     * In a snapshot Bundle, this key reports the proportional set size for
     * dalvik.
     */
    public static final String METRIC_KEY_JAVA_PSS = "java_pss";
    /**
     * In a snapshot Bundle, this key reports the number of shared dirty pages
     * used by dalvik.
     */
    public static final String METRIC_KEY_JAVA_SHARED_DIRTY = "java_shared_dirty";
    /**
     * In a snapshot Bundle, this key reports the total amount of memory
     * available to the running program.
     */
    public static final String METRIC_KEY_JAVA_SIZE = "java_size";
    /**
     * In a snapshot Bundle, this key reports the amount of allocated memory in
     * the native heap.
     */
    public static final String METRIC_KEY_NATIVE_ALLOCATED = "native_allocated";
    /**
     * In a snapshot Bundle, this key reports the amount of free memory in the
     * native heap.
     */
    public static final String METRIC_KEY_NATIVE_FREE = "native_free";
    /**
     * In a snapshot Bundle, this key reports the number of private dirty pages
     * used by the native heap.
     */
    public static final String METRIC_KEY_NATIVE_PRIVATE_DIRTY = "native_private_dirty";
    /**
     * In a snapshot Bundle, this key reports the proportional set size for the
     * native heap.
     */
    public static final String METRIC_KEY_NATIVE_PSS = "native_pss";
    /**
     * In a snapshot Bundle, this key reports the number of shared dirty pages
     * used by the native heap.
     */
    public static final String METRIC_KEY_NATIVE_SHARED_DIRTY = "native_shared_dirty";
    /**
     * In a snapshot Bundle, this key reports the size of the native heap.
     */
    public static final String METRIC_KEY_NATIVE_SIZE = "native_size";
    /**
     * In a snapshot Bundle, this key reports the number of objects allocated
     * globally.
     */
    public static final String METRIC_KEY_GLOBAL_ALLOC_COUNT = "global_alloc_count";
    /**
     * In a snapshot Bundle, this key reports the size of all objects allocated
     * globally.
     */
    public static final String METRIC_KEY_GLOBAL_ALLOC_SIZE = "global_alloc_size";
    /**
     * In a snapshot Bundle, this key reports the number of objects freed
     * globally.
     */
    public static final String METRIC_KEY_GLOBAL_FREED_COUNT = "global_freed_count";
    /**
     * In a snapshot Bundle, this key reports the size of all objects freed
     * globally.
     */
    public static final String METRIC_KEY_GLOBAL_FREED_SIZE = "global_freed_size";
    /**
     * In a snapshot Bundle, this key reports the number of private dirty pages
     * used by everything else.
     */
    public static final String METRIC_KEY_OTHER_PRIVATE_DIRTY = "other_private_dirty";
    /**
     * In a snapshot Bundle, this key reports the proportional set size for
     * everything else.
     */
    public static final String METRIC_KEY_OTHER_PSS = "other_pss";
    /**
     * In a snapshot Bundle, this key reports the number of shared dirty pages
     * used by everything else.
     */
    public static final String METRIC_KEY_OTHER_SHARED_DIRTY = "other_shared_dirty";

    private PerformanceResultsWriter mPerfWriter;
    private Bundle mPerfSnapshot;
    private Bundle mPerfMeasurement;
    private long mSnapshotCpuTime;
    private long mSnapshotExecTime;
    private long mCpuTime;
    private long mExecTime;

    @UnsupportedAppUsage
    public PerformanceCollector() {
    }

    public PerformanceCollector(PerformanceResultsWriter writer) {
        setPerformanceResultsWriter(writer);
    }

    public void setPerformanceResultsWriter(PerformanceResultsWriter writer) {
        mPerfWriter = writer;
    }

    /**
     * Begin collection of memory usage information.
     *
     * @param label description of code block between beginSnapshot and
     *              endSnapshot, used to label output
     */
    @UnsupportedAppUsage
    public void beginSnapshot(String label) {
        if (mPerfWriter != null)
            mPerfWriter.writeBeginSnapshot(label);
        startPerformanceSnapshot();
    }

    /**
     * End collection of memory usage information. Returns collected data in a
     * Bundle object.
     *
     * @return Memory and runtime metrics stored as key/value pairs. Values are
     *         of type long, and keys include:
     *         <ul>
     *         <li>{@link #METRIC_KEY_CPU_TIME cpu_time}
     *         <li>{@link #METRIC_KEY_EXECUTION_TIME execution_time}
     *         <li>{@link #METRIC_KEY_PRE_RECEIVED_TRANSACTIONS
     *         pre_received_transactions}
     *         <li>{@link #METRIC_KEY_PRE_SENT_TRANSACTIONS
     *         pre_sent_transactions}
     *         <li>{@link #METRIC_KEY_RECEIVED_TRANSACTIONS
     *         received_transactions}
     *         <li>{@link #METRIC_KEY_SENT_TRANSACTIONS sent_transactions}
     *         <li>{@link #METRIC_KEY_GC_INVOCATION_COUNT gc_invocation_count}
     *         <li>{@link #METRIC_KEY_JAVA_ALLOCATED java_allocated}
     *         <li>{@link #METRIC_KEY_JAVA_FREE java_free}
     *         <li>{@link #METRIC_KEY_JAVA_PRIVATE_DIRTY java_private_dirty}
     *         <li>{@link #METRIC_KEY_JAVA_PSS java_pss}
     *         <li>{@link #METRIC_KEY_JAVA_SHARED_DIRTY java_shared_dirty}
     *         <li>{@link #METRIC_KEY_JAVA_SIZE java_size}
     *         <li>{@link #METRIC_KEY_NATIVE_ALLOCATED native_allocated}
     *         <li>{@link #METRIC_KEY_NATIVE_FREE native_free}
     *         <li>{@link #METRIC_KEY_NATIVE_PRIVATE_DIRTY native_private_dirty}
     *         <li>{@link #METRIC_KEY_NATIVE_PSS native_pss}
     *         <li>{@link #METRIC_KEY_NATIVE_SHARED_DIRTY native_shared_dirty}
     *         <li>{@link #METRIC_KEY_NATIVE_SIZE native_size}
     *         <li>{@link #METRIC_KEY_GLOBAL_ALLOC_COUNT global_alloc_count}
     *         <li>{@link #METRIC_KEY_GLOBAL_ALLOC_SIZE global_alloc_size}
     *         <li>{@link #METRIC_KEY_GLOBAL_FREED_COUNT global_freed_count}
     *         <li>{@link #METRIC_KEY_GLOBAL_FREED_SIZE global_freed_size}
     *         <li>{@link #METRIC_KEY_OTHER_PRIVATE_DIRTY other_private_dirty}
     *         <li>{@link #METRIC_KEY_OTHER_PSS other_pss}
     *         <li>{@link #METRIC_KEY_OTHER_SHARED_DIRTY other_shared_dirty}
     *         </ul>
     */
    @UnsupportedAppUsage
    public Bundle endSnapshot() {
        endPerformanceSnapshot();
        if (mPerfWriter != null)
            mPerfWriter.writeEndSnapshot(mPerfSnapshot);
        return mPerfSnapshot;
    }

    /**
     * Start measurement of user and cpu time.
     *
     * @param label description of code block between startTiming and
     *        stopTiming, used to label output
     */
    @UnsupportedAppUsage
    public void startTiming(String label) {
        if (mPerfWriter != null)
            mPerfWriter.writeStartTiming(label);
        mPerfMeasurement = new Bundle();
        mPerfMeasurement.putParcelableArrayList(
                METRIC_KEY_ITERATIONS, new ArrayList<Parcelable>());
        mExecTime = SystemClock.uptimeMillis();
        mCpuTime = Process.getElapsedCpuTime();
    }

    /**
     * Add a measured segment, and start measuring the next segment. Returns
     * collected data in a Bundle object.
     *
     * @param label description of code block between startTiming and
     *              addIteration, and between two calls to addIteration, used
     *              to label output
     * @return Runtime metrics stored as key/value pairs. Values are of type
     *         long, and keys include:
     *         <ul>
     *         <li>{@link #METRIC_KEY_LABEL label}
     *         <li>{@link #METRIC_KEY_CPU_TIME cpu_time}
     *         <li>{@link #METRIC_KEY_EXECUTION_TIME execution_time}
     *         </ul>
     */
    public Bundle addIteration(String label) {
        mCpuTime = Process.getElapsedCpuTime() - mCpuTime;
        mExecTime = SystemClock.uptimeMillis() - mExecTime;

        Bundle iteration = new Bundle();
        iteration.putString(METRIC_KEY_LABEL, label);
        iteration.putLong(METRIC_KEY_EXECUTION_TIME, mExecTime);
        iteration.putLong(METRIC_KEY_CPU_TIME, mCpuTime);
        mPerfMeasurement.getParcelableArrayList(METRIC_KEY_ITERATIONS).add(iteration);

        mExecTime = SystemClock.uptimeMillis();
        mCpuTime = Process.getElapsedCpuTime();
        return iteration;
    }

    /**
     * Stop measurement of user and cpu time.
     *
     * @param label description of code block between addIteration or
     *              startTiming and stopTiming, used to label output
     * @return Runtime metrics stored in a bundle, including all iterations
     *         between calls to startTiming and stopTiming. List of iterations
     *         is keyed by {@link #METRIC_KEY_ITERATIONS iterations}.
     */
    @UnsupportedAppUsage
    public Bundle stopTiming(String label) {
        addIteration(label);
        if (mPerfWriter != null)
            mPerfWriter.writeStopTiming(mPerfMeasurement);
        return mPerfMeasurement;
    }

    /**
     * Add an integer type measurement to the collector.
     *
     * @param label short description of the metric that was measured
     * @param value long value of the measurement
     */
    public void addMeasurement(String label, long value) {
        if (mPerfWriter != null)
            mPerfWriter.writeMeasurement(label, value);
    }

    /**
     * Add a float type measurement to the collector.
     *
     * @param label short description of the metric that was measured
     * @param value float value of the measurement
     */
    public void addMeasurement(String label, float value) {
        if (mPerfWriter != null)
            mPerfWriter.writeMeasurement(label, value);
    }

    /**
     * Add a string field to the collector.
     *
     * @param label short description of the metric that was measured
     * @param value string summary of the measurement
     */
    public void addMeasurement(String label, String value) {
        if (mPerfWriter != null)
            mPerfWriter.writeMeasurement(label, value);
    }

    /*
     * Starts tracking memory usage, binder transactions, and real & cpu timing.
     */
    private void startPerformanceSnapshot() {
        // Create new snapshot
        mPerfSnapshot = new Bundle();

        // Add initial binder counts
        Bundle binderCounts = getBinderCounts();
        for (String key : binderCounts.keySet()) {
            mPerfSnapshot.putLong("pre_" + key, binderCounts.getLong(key));
        }

        // Force a GC and zero out the performance counters. Do this
        // before reading initial CPU/wall-clock times so we don't include
        // the cost of this setup in our final metrics.
        startAllocCounting();

        // Record CPU time up to this point, and start timing. Note: this
        // must happen at the end of this method, otherwise the timing will
        // include noise.
        mSnapshotExecTime = SystemClock.uptimeMillis();
        mSnapshotCpuTime = Process.getElapsedCpuTime();
    }

    /*
     * Stops tracking memory usage, binder transactions, and real & cpu timing.
     * Stores collected data as type long into Bundle object for reporting.
     */
    private void endPerformanceSnapshot() {
        // Stop the timing. This must be done first before any other counting is
        // stopped.
        mSnapshotCpuTime = Process.getElapsedCpuTime() - mSnapshotCpuTime;
        mSnapshotExecTime = SystemClock.uptimeMillis() - mSnapshotExecTime;

        stopAllocCounting();

        long nativeMax = Debug.getNativeHeapSize() / 1024;
        long nativeAllocated = Debug.getNativeHeapAllocatedSize() / 1024;
        long nativeFree = Debug.getNativeHeapFreeSize() / 1024;

        Debug.MemoryInfo memInfo = new Debug.MemoryInfo();
        Debug.getMemoryInfo(memInfo);

        Runtime runtime = Runtime.getRuntime();

        long dalvikMax = runtime.totalMemory() / 1024;
        long dalvikFree = runtime.freeMemory() / 1024;
        long dalvikAllocated = dalvikMax - dalvikFree;

        // Add final binder counts
        Bundle binderCounts = getBinderCounts();
        for (String key : binderCounts.keySet()) {
            mPerfSnapshot.putLong(key, binderCounts.getLong(key));
        }

        // Add alloc counts
        Bundle allocCounts = getAllocCounts();
        for (String key : allocCounts.keySet()) {
            mPerfSnapshot.putLong(key, allocCounts.getLong(key));
        }

        mPerfSnapshot.putLong(METRIC_KEY_EXECUTION_TIME, mSnapshotExecTime);
        mPerfSnapshot.putLong(METRIC_KEY_CPU_TIME, mSnapshotCpuTime);

        mPerfSnapshot.putLong(METRIC_KEY_NATIVE_SIZE, nativeMax);
        mPerfSnapshot.putLong(METRIC_KEY_NATIVE_ALLOCATED, nativeAllocated);
        mPerfSnapshot.putLong(METRIC_KEY_NATIVE_FREE, nativeFree);
        mPerfSnapshot.putLong(METRIC_KEY_NATIVE_PSS, memInfo.nativePss);
        mPerfSnapshot.putLong(METRIC_KEY_NATIVE_PRIVATE_DIRTY, memInfo.nativePrivateDirty);
        mPerfSnapshot.putLong(METRIC_KEY_NATIVE_SHARED_DIRTY, memInfo.nativeSharedDirty);

        mPerfSnapshot.putLong(METRIC_KEY_JAVA_SIZE, dalvikMax);
        mPerfSnapshot.putLong(METRIC_KEY_JAVA_ALLOCATED, dalvikAllocated);
        mPerfSnapshot.putLong(METRIC_KEY_JAVA_FREE, dalvikFree);
        mPerfSnapshot.putLong(METRIC_KEY_JAVA_PSS, memInfo.dalvikPss);
        mPerfSnapshot.putLong(METRIC_KEY_JAVA_PRIVATE_DIRTY, memInfo.dalvikPrivateDirty);
        mPerfSnapshot.putLong(METRIC_KEY_JAVA_SHARED_DIRTY, memInfo.dalvikSharedDirty);

        mPerfSnapshot.putLong(METRIC_KEY_OTHER_PSS, memInfo.otherPss);
        mPerfSnapshot.putLong(METRIC_KEY_OTHER_PRIVATE_DIRTY, memInfo.otherPrivateDirty);
        mPerfSnapshot.putLong(METRIC_KEY_OTHER_SHARED_DIRTY, memInfo.otherSharedDirty);
    }

    /*
     * Starts allocation counting. This triggers a gc and resets the counts.
     */
    private static void startAllocCounting() {
        // Before we start trigger a GC and reset the debug counts. Run the
        // finalizers and another GC before starting and stopping the alloc
        // counts. This will free up any objects that were just sitting around
        // waiting for their finalizers to be run.
        Runtime.getRuntime().gc();
        Runtime.getRuntime().runFinalization();
        Runtime.getRuntime().gc();

        Debug.resetAllCounts();

        // start the counts
        Debug.startAllocCounting();
    }

    /*
     * Stops allocation counting.
     */
    private static void stopAllocCounting() {
        Runtime.getRuntime().gc();
        Runtime.getRuntime().runFinalization();
        Runtime.getRuntime().gc();
        Debug.stopAllocCounting();
    }

    /*
     * Returns a bundle with the current results from the allocation counting.
     */
    private static Bundle getAllocCounts() {
        Bundle results = new Bundle();
        results.putLong(METRIC_KEY_GLOBAL_ALLOC_COUNT, Debug.getGlobalAllocCount());
        results.putLong(METRIC_KEY_GLOBAL_ALLOC_SIZE, Debug.getGlobalAllocSize());
        results.putLong(METRIC_KEY_GLOBAL_FREED_COUNT, Debug.getGlobalFreedCount());
        results.putLong(METRIC_KEY_GLOBAL_FREED_SIZE, Debug.getGlobalFreedSize());
        results.putLong(METRIC_KEY_GC_INVOCATION_COUNT, Debug.getGlobalGcInvocationCount());
        return results;
    }

    /*
     * Returns a bundle with the counts for various binder counts for this
     * process. Currently the only two that are reported are the number of send
     * and the number of received transactions.
     */
    private static Bundle getBinderCounts() {
        Bundle results = new Bundle();
        results.putLong(METRIC_KEY_SENT_TRANSACTIONS, Debug.getBinderSentTransactions());
        results.putLong(METRIC_KEY_RECEIVED_TRANSACTIONS, Debug.getBinderReceivedTransactions());
        return results;
    }
}
