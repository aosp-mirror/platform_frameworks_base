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

package com.android.internal.os;

import static com.android.internal.os.BinderLatencyProto.Dims.SYSTEM_SERVER;

import android.annotation.Nullable;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BinderInternal.CallSession;
import com.android.internal.os.BinderLatencyProto.ApiStats;
import com.android.internal.os.BinderLatencyProto.Dims;
import com.android.internal.os.BinderLatencyProto.RepeatedApiStats;
import com.android.internal.util.FrameworkStatsLog;

import java.util.Random;

/** Collects statistics about Binder call latency per calling API and method. */
public class BinderLatencyObserver {
    private static final String TAG = "BinderLatencyObserver";
    private static final int MAX_ATOM_SIZE_BYTES = 4064;
    // Be conservative and leave 1K space for the last histogram so we don't go over the size limit.
    private static final int LAST_HISTOGRAM_BUFFER_SIZE_BYTES = 1000;

    // Latency observer parameters.
    public static final int PERIODIC_SAMPLING_INTERVAL_DEFAULT = 10;
    public static final int STATSD_PUSH_INTERVAL_MINUTES_DEFAULT = 360;

    // Histogram buckets parameters.
    public static final int BUCKET_COUNT_DEFAULT = 100;
    public static final int FIRST_BUCKET_SIZE_DEFAULT = 5;
    public static final float BUCKET_SCALE_FACTOR_DEFAULT = 1.125f;

    @GuardedBy("mLock")
    private final ArrayMap<LatencyDims, int[]> mLatencyHistograms = new ArrayMap<>();
    private final Object mLock = new Object();

    // Sampling period to control how often to track CPU usage. 1 means all calls, 100 means ~1 out
    // of 100 requests.
    private int mPeriodicSamplingInterval = PERIODIC_SAMPLING_INTERVAL_DEFAULT;

    private int mBucketCount = BUCKET_COUNT_DEFAULT;
    private int mFirstBucketSize = FIRST_BUCKET_SIZE_DEFAULT;
    private float mBucketScaleFactor = BUCKET_SCALE_FACTOR_DEFAULT;

    private int mStatsdPushIntervalMinutes = STATSD_PUSH_INTERVAL_MINUTES_DEFAULT;

    private final Random mRandom;
    private BinderLatencyBuckets mLatencyBuckets;

    private final Handler mLatencyObserverHandler;

    private Runnable mLatencyObserverRunnable = new Runnable() {
        @Override
        public void run() {
            // Schedule the next push.
            noteLatencyDelayed();

            ArrayMap<LatencyDims, int[]> histogramMap;
            synchronized (mLock) {
                // Copy the histograms map so we don't use the lock for longer than needed.
                histogramMap = new ArrayMap<>(mLatencyHistograms);
                mLatencyHistograms.clear();
            }

            BinderTransactionNameResolver resolver = new BinderTransactionNameResolver();
            ProtoOutputStream proto = new ProtoOutputStream();
            int histogramsWritten = 0;

            for (LatencyDims dims : histogramMap.keySet()) {
                // Start a new atom if the next histogram risks going over the atom size limit.
                if (proto.getRawSize() + LAST_HISTOGRAM_BUFFER_SIZE_BYTES > getMaxAtomSizeBytes()) {
                    if (histogramsWritten > 0) {
                        writeAtomToStatsd(proto);
                    }
                    proto = new ProtoOutputStream();
                    histogramsWritten = 0;
                }

                String transactionName = resolver.getMethodName(
                        dims.getBinderClass(), dims.getTransactionCode());
                fillApiStatsProto(proto, dims, transactionName, histogramMap.get(dims));
                histogramsWritten++;
            }
            // Push the final atom.
            if (histogramsWritten > 0) {
                writeAtomToStatsd(proto);
            }
        }
    };

    private void fillApiStatsProto(
            ProtoOutputStream proto, LatencyDims dims, String transactionName, int[] histogram) {
        // Find the part of the histogram to write.
        int firstNonEmptyBucket = 0;
        for (int i = 0; i < mBucketCount; i++) {
            if (histogram[i] != 0) {
                firstNonEmptyBucket = i;
                break;
            }
        }
        int lastNonEmptyBucket = mBucketCount - 1;
        for (int i = mBucketCount - 1; i >= 0; i--) {
            if (histogram[i] != 0) {
                lastNonEmptyBucket = i;
                break;
            }
        }

        // Start a new ApiStats proto.
        long apiStatsToken = proto.start(RepeatedApiStats.API_STATS);

        // Write the dims.
        long dimsToken = proto.start(ApiStats.DIMS);
        proto.write(Dims.PROCESS_SOURCE, SYSTEM_SERVER);
        proto.write(Dims.SERVICE_CLASS_NAME, dims.getBinderClass().getName());
        proto.write(Dims.SERVICE_METHOD_NAME, transactionName);
        proto.end(dimsToken);

        // Write the histogram.
        proto.write(ApiStats.FIRST_BUCKET_INDEX, firstNonEmptyBucket);
        for (int i = firstNonEmptyBucket; i <= lastNonEmptyBucket; i++) {
            proto.write(ApiStats.BUCKETS, histogram[i]);
        }

        proto.end(apiStatsToken);
    }

    protected int getMaxAtomSizeBytes() {
        return MAX_ATOM_SIZE_BYTES;
    }

    protected void writeAtomToStatsd(ProtoOutputStream atom) {
        FrameworkStatsLog.write(
                FrameworkStatsLog.BINDER_LATENCY_REPORTED,
                atom.getBytes(),
                mPeriodicSamplingInterval,
                1,
                mBucketCount,
                mFirstBucketSize,
                mBucketScaleFactor);
    }

    private void noteLatencyDelayed() {
        mLatencyObserverHandler.removeCallbacks(mLatencyObserverRunnable);
        mLatencyObserverHandler.postDelayed(mLatencyObserverRunnable,
                mStatsdPushIntervalMinutes * 60 * 1000);
    }

    /** Injector for {@link BinderLatencyObserver}. */
    public static class Injector {
        public Random getRandomGenerator() {
            return new Random();
        }

        public Handler getHandler() {
            return new Handler(Looper.getMainLooper());
        }
    }

    public BinderLatencyObserver(Injector injector) {
        mRandom = injector.getRandomGenerator();
        mLatencyObserverHandler = injector.getHandler();
        mLatencyBuckets = new BinderLatencyBuckets(
            mBucketCount, mFirstBucketSize, mBucketScaleFactor);
        noteLatencyDelayed();
    }

    /** Should be called when a Binder call completes, will store latency data. */
    public void callEnded(@Nullable CallSession s) {
        if (s == null || s.exceptionThrown || !shouldKeepSample()) {
            return;
        }

        LatencyDims dims = new LatencyDims(s.binderClass, s.transactionCode);
        long elapsedTimeMicro = getElapsedRealtimeMicro();
        long callDuration = elapsedTimeMicro - s.timeStarted;

        // Find the bucket this sample should go to.
        int bucketIdx = mLatencyBuckets.sampleToBucket(
                callDuration > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) callDuration);

        synchronized (mLock) {
            int[] buckets = mLatencyHistograms.get(dims);
            if (buckets == null) {
                buckets = new int[mBucketCount];
                mLatencyHistograms.put(dims, buckets);
            }

            // Increment the correct bucket.
            if (buckets[bucketIdx] < Integer.MAX_VALUE) {
                buckets[bucketIdx] += 1;
            }
        }
    }

    protected long getElapsedRealtimeMicro() {
        return SystemClock.elapsedRealtimeNanos() / 1000;
    }

    protected boolean shouldKeepSample() {
        return mRandom.nextInt() % mPeriodicSamplingInterval == 0;
    }

    /** Updates the sampling interval. */
    public void setSamplingInterval(int samplingInterval) {
        if (samplingInterval <= 0) {
            Slog.w(TAG, "Ignored invalid sampling interval (value must be positive): "
                    + samplingInterval);
            return;
        }

        synchronized (mLock) {
            if (samplingInterval != mPeriodicSamplingInterval) {
                mPeriodicSamplingInterval = samplingInterval;
                reset();
            }
        }
    }

    /** Updates the statsd push interval. */
    public void setPushInterval(int pushIntervalMinutes) {
        if (pushIntervalMinutes <= 0) {
            Slog.w(TAG, "Ignored invalid push interval (value must be positive): "
                    + pushIntervalMinutes);
            return;
        }

        synchronized (mLock) {
            if (pushIntervalMinutes != mStatsdPushIntervalMinutes) {
                mStatsdPushIntervalMinutes = pushIntervalMinutes;
                reset();
            }
        }
    }

    /** Updates the histogram buckets parameters. */
    public void setHistogramBucketsParams(
            int bucketCount, int firstBucketSize, float bucketScaleFactor) {
        synchronized (mLock) {
            if (bucketCount != mBucketCount || firstBucketSize != mFirstBucketSize
                    || bucketScaleFactor != mBucketScaleFactor) {
                mBucketCount = bucketCount;
                mFirstBucketSize = firstBucketSize;
                mBucketScaleFactor = bucketScaleFactor;
                mLatencyBuckets = new BinderLatencyBuckets(
                    mBucketCount, mFirstBucketSize, mBucketScaleFactor);
                reset();
            }
        }
    }

    /** Resets the sample collection. */
    public void reset() {
        synchronized (mLock) {
            mLatencyHistograms.clear();
        }
        noteLatencyDelayed();
    }

    /** Container for binder latency information. */
    public static class LatencyDims {
        // Binder interface descriptor.
        private Class<? extends Binder> mBinderClass;
        // Binder transaction code.
        private int mTransactionCode;
        // Cached hash code, 0 if not set yet.
        private int mHashCode = 0;

        public LatencyDims(Class<? extends Binder> binderClass, int transactionCode) {
            this.mBinderClass = binderClass;
            this.mTransactionCode = transactionCode;
        }

        public Class<? extends Binder> getBinderClass() {
            return mBinderClass;
        }

        public int getTransactionCode() {
            return mTransactionCode;
        }

        @Override
        public boolean equals(final Object other) {
            if (other == null || !(other instanceof LatencyDims)) {
                return false;
            }
            LatencyDims o = (LatencyDims) other;
            return mTransactionCode == o.getTransactionCode() && mBinderClass == o.getBinderClass();
        }

        @Override
        public int hashCode() {
            if (mHashCode != 0) {
                return mHashCode;
            }
            int hash = mTransactionCode;
            hash = 31 * hash + mBinderClass.hashCode();
            mHashCode = hash;
            return hash;
        }
    }

    @VisibleForTesting
    public ArrayMap<LatencyDims, int[]> getLatencyHistograms() {
        return mLatencyHistograms;
    }

    @VisibleForTesting
    public Runnable getStatsdPushRunnable() {
        return mLatencyObserverRunnable;
    }
}
