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

package com.android.internal.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.HeavyHitterSketch;

import java.util.ArrayList;
import java.util.List;

/**
 * A watcher which makes stats on the incoming binder transaction, if the amount of some type of
 * transactions exceeds the threshold, the listener will be notified.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class BinderCallHeavyHitterWatcher {
    private static final String TAG = "BinderCallHeavyHitterWatcher";

    /**
     * Whether or not this watcher is enabled.
     */
    @GuardedBy("mLock")
    private boolean mEnabled;

    /**
     * The listener to be notified in case the amount of some type of transactions exceeds the
     * threshold.
     */
    @GuardedBy("mLock")
    private BinderCallHeavyHitterListener mListener;

    /**
     * The heavy hitter stats.
     */
    @GuardedBy("mLock")
    private HeavyHitterSketch<Integer> mHeavyHitterSketch;

    /**
     * The candidates that could be the heavy hitters, so we track their hashcode and the actual
     * containers in this map.
     */
    @GuardedBy("mLock")
    private final SparseArray<HeavyHitterContainer> mHeavyHitterCandiates = new SparseArray<>();

    /**
     * The cache to receive the list of candidates (consists of the hashcode of heavy hitters).
     */
    @GuardedBy("mLock")
    private final ArrayList<Integer> mCachedCandidateList = new ArrayList<>();

    /**
     * The cache to receive the frequencies of each items in {@link #mCachedCandidateList}.
     */
    @GuardedBy("mLock")
    private final ArrayList<Float> mCachedCandidateFrequencies = new ArrayList<>();

    /**
     * The cache set to host the candidates.
     */
    @GuardedBy("mLock")
    private ArraySet<Integer> mCachedCandidateSet = new ArraySet<>();

    /**
     * The cache set to host the containers of candidates.
     */
    @GuardedBy("mLock")
    private HeavyHitterContainer[] mCachedCandidateContainers;

    /**
     * The index to the {@link #mCachedCandidateContainers}, denote the first available slot
     */
    @GuardedBy("mLock")
    private int mCachedCandidateContainersIndex;

    /**
     * The input size, should be {@link #mTotalInputSize} - validation size.
     */
    @GuardedBy("mLock")
    private int mInputSize;

    /**
     * The total input size.
     */
    @GuardedBy("mLock")
    private int mTotalInputSize;

    /**
     * The number of inputs so far
     */
    @GuardedBy("mLock")
    private int mCurrentInputSize;

    /**
     * The threshold to be considered as heavy hitters
     */
    @GuardedBy("mLock")
    private float mThreshold;

    /**
     * The timestamp of the start of current tracing.
     */
    @GuardedBy("mLock")
    private long mBatchStartTimeStamp;

    /**
     * The lock object
     */
    private final Object mLock = new Object();

    /**
     * The tolerance within which is approximately equal
     */
    private static final float EPSILON = 0.00001f;

    /**
     * Callback interface when the amount of some type of transactions exceeds the threshold.
     */
    public interface BinderCallHeavyHitterListener {
        /**
         * @param heavyHitters     The list of binder call heavy hitters
         * @param totalBinderCalls The total binder calls
         * @param threshold        The threshold to be considered as heavy hitters
         * @param timeSpan         The toal time span of all these binder calls
         */
        void onHeavyHit(List<HeavyHitterContainer> heavyHitters,
                int totalBinderCalls, float threshold, long timeSpan);
    }

    /**
     * Container to hold the potential heavy hitters
     */
    public static final class HeavyHitterContainer {
        /**
         * The caller UID
         */
        public int mUid;

        /**
         * The class of the Binder object which is being hit heavily
         */
        public Class mClass;

        /**
         * The transaction code within the Binder object which is being hit heavily
         */
        public int mCode;

        /**
         * The frequency of this being hit (a number between 0...1)
         */
        public float mFrequency;

        /**
         * Default constructor
         */
        public HeavyHitterContainer() {
        }

        /**
         * Copy constructor
         */
        public HeavyHitterContainer(@NonNull final HeavyHitterContainer other) {
            this.mUid = other.mUid;
            this.mClass = other.mClass;
            this.mCode = other.mCode;
            this.mFrequency = other.mFrequency;
        }

        @Override
        public boolean equals(final Object other) {
            if (other == null || !(other instanceof HeavyHitterContainer)) {
                return false;
            }
            HeavyHitterContainer o = (HeavyHitterContainer) other;
            return this.mUid == o.mUid && this.mClass == o.mClass && this.mCode == o.mCode
                    && Math.abs(this.mFrequency - o.mFrequency) < EPSILON;
        }

        @Override
        public int hashCode() {
            return hashCode(mUid, mClass, mCode);
        }

        /**
         * Compute the hashcode with given parameters.
         */
        static int hashCode(int uid, @NonNull Class clazz, int code) {
            int hash = uid;
            hash = 31 * hash + clazz.hashCode();
            hash = 31 * hash + code;
            return hash;
        }
    }

    /**
     * The static lock object
     */
    private static final Object sLock = new Object();

    /**
     * The default instance
     */
    @GuardedBy("sLock")
    private static BinderCallHeavyHitterWatcher sInstance = null;

    /**
     * Return the instance of the watcher
     */
    public static BinderCallHeavyHitterWatcher getInstance() {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new BinderCallHeavyHitterWatcher();
            }
            return sInstance;
        }
    }

    /**
     * Configure the parameters.
     *
     * @param enable    Whether or not to enable the watcher
     * @param batchSize The number of binder transactions it needs to receive before the conclusion
     * @param threshold The threshold to determine if some type of transactions are too many, it
     *                  should be a value between (0.0f, 1.0f]
     * @param listener  The callback interface
     */
    public void setConfig(final boolean enable, final int batchSize, final float threshold,
            @Nullable final BinderCallHeavyHitterListener listener) {
        synchronized (mLock) {
            if (!enable) {
                if (mEnabled) {
                    resetInternalLocked(null, null, 0, 0, 0.0f, 0);
                    mEnabled = false;
                }
                return;
            }
            mEnabled = true;
            // Validate the threshold, which is expected to be within (0.0f, 1.0f]
            if (threshold < EPSILON || threshold > 1.0f) {
                return;
            }

            if (batchSize == mTotalInputSize && Math.abs(threshold - mThreshold) < EPSILON) {
                // Shortcut: just update the listener, no need to reset the watcher itself.
                mListener = listener;
                return;
            }

            final int capacity = (int) (1.0f / threshold);
            final HeavyHitterSketch<Integer> sketch = HeavyHitterSketch.<Integer>newDefault();
            final float validationRatio = sketch.getRequiredValidationInputRatio();
            int inputSize = batchSize;
            if (!Float.isNaN(validationRatio)) {
                inputSize = (int) (batchSize * (1 - validationRatio));
            }
            try {
                sketch.setConfig(batchSize, capacity);
            } catch (IllegalArgumentException e) {
                // invalid parameter, ignore the config.
                Log.w(TAG, "Invalid parameter to heavy hitter watcher: "
                        + batchSize + ", " + capacity);
                return;
            }
            // Reset the watcher to start over with the new configuration.
            resetInternalLocked(listener, sketch, inputSize, batchSize, threshold, capacity);
        }
    }

    @GuardedBy("mLock")
    private void resetInternalLocked(@Nullable final BinderCallHeavyHitterListener listener,
            @Nullable final HeavyHitterSketch<Integer> sketch, final int inputSize,
            final int batchSize, final float threshold, final int capacity) {
        mListener = listener;
        mHeavyHitterSketch = sketch;
        mHeavyHitterCandiates.clear();
        mCachedCandidateList.clear();
        mCachedCandidateFrequencies.clear();
        mCachedCandidateSet.clear();
        mInputSize = inputSize;
        mTotalInputSize = batchSize;
        mCurrentInputSize = 0;
        mThreshold = threshold;
        mBatchStartTimeStamp = SystemClock.elapsedRealtime();
        initCachedCandidateContainersLocked(capacity);
    }

    @GuardedBy("mLock")
    private void initCachedCandidateContainersLocked(final int capacity) {
        if (capacity > 0) {
            mCachedCandidateContainers = new HeavyHitterContainer[capacity];
            for (int i = 0; i < mCachedCandidateContainers.length; i++) {
                mCachedCandidateContainers[i] = new HeavyHitterContainer();
            }
        } else {
            mCachedCandidateContainers = null;
        }
        mCachedCandidateContainersIndex = 0;
    }

    @GuardedBy("mLock")
    private @NonNull HeavyHitterContainer acquireHeavyHitterContainerLocked() {
        return mCachedCandidateContainers[mCachedCandidateContainersIndex++];
    }

    @GuardedBy("mLock")
    private void releaseHeavyHitterContainerLocked(@NonNull HeavyHitterContainer container) {
        mCachedCandidateContainers[--mCachedCandidateContainersIndex] = container;
    }

    /**
     * Called on incoming binder transaction
     *
     * @param callerUid The UID of the binder transaction's caller
     * @param clazz     The class of the Binder object serving the transaction
     * @param code      The binder transaction code
     */
    public void onTransaction(final int callerUid, @NonNull final Class clazz,
            final int code) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }

            final HeavyHitterSketch<Integer> sketch = mHeavyHitterSketch;
            if (sketch == null) {
                return;
            }

            // To reduce memory fragmentation, we only feed the hashcode to the sketch,
            // and keep the mapping from the hashcode to the sketch locally.
            // However, the mapping will not be built until the validation pass, by then
            // we will know the potential heavy hitters, so the mapping can focus on
            // those ones, which will significantly reduce the memory overhead.
            final int hashCode = HeavyHitterContainer.hashCode(callerUid, clazz, code);

            sketch.add(hashCode);
            mCurrentInputSize++;
            if (mCurrentInputSize == mInputSize) {
                // Retrieve the candidates
                sketch.getCandidates(mCachedCandidateList);
                mCachedCandidateSet.addAll(mCachedCandidateList);
                mCachedCandidateList.clear();
            } else if (mCurrentInputSize > mInputSize && mCurrentInputSize < mTotalInputSize) {
                // validation pass
                if (mCachedCandidateSet.contains(hashCode)) {
                    // It's one of the candidates
                    final int index = mHeavyHitterCandiates.indexOfKey(hashCode);
                    if (index < 0) {
                        // We got another hit, now write down its information
                        final HeavyHitterContainer container =
                                acquireHeavyHitterContainerLocked();
                        container.mUid = callerUid;
                        container.mClass = clazz;
                        container.mCode = code;
                        mHeavyHitterCandiates.put(hashCode, container);
                    }
                }
            } else if (mCurrentInputSize == mTotalInputSize) {
                // Reached the expected number of input, check top ones
                if (mListener != null) {
                    final List<Integer> result = sketch.getTopHeavyHitters(0,
                            mCachedCandidateList, mCachedCandidateFrequencies);
                    if (result != null) {
                        final int size = result.size();
                        if (size > 0) {
                            final ArrayList<HeavyHitterContainer> hitters = new ArrayList<>();
                            for (int i = 0; i < size; i++) {
                                final HeavyHitterContainer container = mHeavyHitterCandiates.get(
                                        result.get(i));
                                if (container != null) {
                                    final HeavyHitterContainer cont =
                                            new HeavyHitterContainer(container);
                                    cont.mFrequency = mCachedCandidateFrequencies.get(i);
                                    hitters.add(cont);
                                }
                            }
                            mListener.onHeavyHit(hitters, mTotalInputSize, mThreshold,
                                    SystemClock.elapsedRealtime() - mBatchStartTimeStamp);
                        }
                    }
                }
                // reset
                mHeavyHitterSketch.reset();
                mHeavyHitterCandiates.clear();
                mCachedCandidateList.clear();
                mCachedCandidateFrequencies.clear();
                mCachedCandidateSet.clear();
                mCachedCandidateContainersIndex = 0;
                mCurrentInputSize = 0;
                mBatchStartTimeStamp = SystemClock.elapsedRealtime();
            }
        }
    }
}
