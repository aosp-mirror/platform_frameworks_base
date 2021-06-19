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

package com.android.internal.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.SparseArray;
import android.util.SparseIntArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <p>A utility which processes a sequence of input (stream) and output the heavy hitters
 * (the frequent ones).</p>
 *
 * @param <T> The type of the input.
 * @see <a href="https://en.wikipedia.org/wiki/Streaming_algorithm">Stream Algorithm</a> for
 * the definion of heavy hitters and the list of algorithms for detecting it.
 * <p>
 * {@hide}
 */
public interface HeavyHitterSketch<T> {
    /**
     * Return the default implementation.
     *
     * @return The default implementation.
     */
    static <V> @NonNull HeavyHitterSketch<V> newDefault() {
        return new HeavyHitterSketchImpl<V>();
    }

    /**
     * Set the configuration with given parameters
     *
     * @param inputSize The amount of the input.
     * @param capacity  The maximum number of distinct input it should track; it defines the lower
     *                  bound of the output.
     */
    void setConfig(int inputSize, int capacity);

    /**
     * Add a new input to the current sketch.
     *
     * @param newInstance The new input
     */
    void add(@Nullable T newInstance);

    /**
     * @param k      The number of heavy hitters it should return, k &lt; capacity, a value of 0
     *               will be equivalent to capacity - 1
     * @param holder The list array into which the elements of the tops are to be stored; a new list
     *               would be created and returned if this parameter is null.
     * @param freqs  Optional, the frequencies of each items in the returned result
     * @return The top K heavy hitters(frequent input)
     */
    @Nullable
    List<T> getTopHeavyHitters(int k, @Nullable List<T> holder, @Nullable List<Float> freqs);

    /**
     * @param holder The list array into which the elements of the candidates are to be stored; a
     *               new list would be created and returned if this parameter is null.
     * @return The candidate heavy hitters so far, it could include false postives.
     */
    @Nullable
    List<T> getCandidates(@Nullable List<T> holder);

    /**
     * Reset this heavy hitter counter
     */
    void reset();

    /**
     * @return The ratio of the input to be used as the validation data, Float.NaN means no
     * validation is needed.
     */
    float getRequiredValidationInputRatio();

    /**
     * The default implementation of the {@link HeavyHitterSketch}.
     *
     * <p>Currently it consists of two passes: the first pass will take the input into
     * the MG(Misra–Gries) summary; while the secondary pass will validate the output against the
     * input in order to eliminate false postivies.</p>
     *
     * <p>For sure there are better approaches which takes only one pass, but also comes along with
     * overheads in terms of cpu/memory cost; the MG summary would be a trivial and good enough
     * pick.</p>
     *
     * @param <T> The type of the input.
     * @see <a href="https://en.wikipedia.org/wiki/Misra%E2%80%93Gries_summary">Misra–Gries
     * summary</a> for the detailed explanation of the algorithm.
     */
    final class HeavyHitterSketchImpl<T> implements HeavyHitterSketch<T> {
        /**
         * The array to track the current heavy hitters, its size &lt; {@link #mCapacity}.
         */
        private final SparseArray<T> mObjects = new SparseArray<>();

        /**
         * The frequencies of the current heavy hitters, its size &lt; {@link #mCapacity}.
         */
        private final SparseIntArray mFrequencies = new SparseIntArray();

        /**
         * The amount of the input of each pass
         */
        private int mPassSize;

        /**
         * The amount of the total input it expects
         */
        private int mTotalSize;

        /**
         * The maximum number of distinct input it should track
         */
        private int mCapacity;

        /**
         * The amount of inputs it already received.
         */
        private int mNumInputs;

        /**
         * Whether or not it's configured properly.
         */
        private boolean mConfigured;

        /**
         * Set the configuration with given parameters
         *
         * @param inputSize The amount of the input.
         * @param capacity  The maximum number of distinct input it should track; it defines the
         *                  lower bound of the output.
         */
        public void setConfig(final int inputSize, final int capacity) {
            if (inputSize < capacity || inputSize <= 1) {
                mConfigured = false;
                throw new IllegalArgumentException();
            }
            reset();
            mTotalSize = inputSize;
            mPassSize = inputSize >> 1;
            mCapacity = capacity;
            mConfigured = true;
        }

        /**
         * Add a new input to the current sketch.
         *
         * @param newInstance The new input
         */
        @Override
        public void add(@Nullable final T newInstance) {
            if (!mConfigured) {
                throw new IllegalStateException();
            }
            if (mNumInputs < mPassSize) {
                addToMGSummary(newInstance);
            } else if (mNumInputs < mTotalSize) {
                // Validation pass
                validate(newInstance);
            }
        }

        /**
         * Add an input to the MG summary.
         *
         * <p>Note the frequency in the result set is an estimation. Every (obj, freq') pair
         * in the result set, will have the following property:
         * <code>(freq - inputSize / capacity) &le; freq' &le; freq</code>
         * The above freq' is the estimated frequency, while the freq is the actual frequency.
         * </p>
         */
        private void addToMGSummary(@Nullable final T newInstance) {
            final int hashCode = newInstance != null ? newInstance.hashCode() : 0;
            final int index = mObjects.indexOfKey(hashCode);
            // MG summary
            if (index >= 0) {
                mFrequencies.setValueAt(index, mFrequencies.valueAt(index) + 1);
            } else if (mObjects.size() < mCapacity - 1) {
                mObjects.put(hashCode, newInstance);
                mFrequencies.put(hashCode, 1);
            } else {
                for (int i = mFrequencies.size() - 1; i >= 0; i--) {
                    final int val = mFrequencies.valueAt(i) - 1;
                    if (val == 0) {
                        mObjects.removeAt(i);
                        mFrequencies.removeAt(i);
                    } else {
                        mFrequencies.setValueAt(i, val);
                    }
                }
            }
            if (++mNumInputs == mPassSize) {
                // Clear all the frequencies as we are going to validate them next
                for (int i = mFrequencies.size() - 1; i >= 0; i--) {
                    mFrequencies.setValueAt(i, 0);
                }
            }
        }

        /**
         * Validate the results from MG summary; the ones with frequencies less than lower boundary
         * will be removed from the set.
         */
        private void validate(@Nullable final T newInstance) {
            final int hashCode = newInstance != null ? newInstance.hashCode() : 0;
            final int index = mObjects.indexOfKey(hashCode);
            if (index >= 0) {
                mFrequencies.setValueAt(index, mFrequencies.valueAt(index) + 1);
            }
            if (++mNumInputs == mTotalSize) {
                final int lower = mPassSize / mCapacity;
                // Remove any occurrences with frequencies less than lower boundary
                for (int i = mFrequencies.size() - 1; i >= 0; i--) {
                    final int val = mFrequencies.valueAt(i);
                    if (val < lower) {
                        mFrequencies.removeAt(i);
                        mObjects.removeAt(i);
                    }
                }
            }
        }

        /**
         * @param k      The number of heavy hitters it should return, k &lt; capacity, a value of 0
         *               will be equivalent to capacity - 1
         * @param holder The list array into which the elements of the tops are to be stored; a new
         *               list would be created and returned if this parameter is null.
         * @param freqs  Optional, the frequencies of each items in the returned result
         * @return The top K heavy hitters(frequent input)
         */
        @Override
        @Nullable
        public List<T> getTopHeavyHitters(final int k, final @Nullable List<T> holder,
                final @Nullable List<Float> freqs) {
            if (!mConfigured) {
                throw new IllegalStateException();
            }

            if (k >= mCapacity) {
                throw new IllegalArgumentException();
            }

            if (mNumInputs < mTotalSize) {
                // It hasn't had all the inputs yet.
                throw new IllegalStateException();
            }

            ArrayList<Integer> indexes = null;
            for (int i = mFrequencies.size() - 1; i >= 0; i--) {
                final int val = mFrequencies.valueAt(i);
                if (val > 0) {
                    if (indexes == null) {
                        indexes = new ArrayList<>();
                    }
                    indexes.add(i);
                }
            }
            if (indexes == null) {
                return null;
            }

            Collections.sort(indexes, (a, b) -> mFrequencies.valueAt(b) - mFrequencies.valueAt(a));

            final List<T> result = holder != null ? holder : new ArrayList<T>();
            final int max = Math.min(k == 0 ? (mCapacity - 1) : k, indexes.size());
            for (int i = 0; i < max; i++) {
                final int index = indexes.get(i);
                final T obj = mObjects.valueAt(index);
                if (obj != null) {
                    result.add(obj);
                    if (freqs != null) {
                        freqs.add((float) mFrequencies.valueAt(index) / mPassSize);
                    }
                }
            }
            return result;
        }

        /**
         * @param holder The list array into which the elements of the candidates are to be stored;
         *               a new list would be created and returned if this parameter is null.
         * @return The candidate heavy hitters so far, it could include false postives.
         */
        @Nullable
        public List<T> getCandidates(final @Nullable List<T> holder) {
            if (!mConfigured) {
                throw new IllegalStateException();
            }
            if (mNumInputs < mPassSize) {
                // It hasn't done with the first pass yet, return nothing
                return null;
            }

            List<T> result = holder != null ? holder : new ArrayList<T>();
            for (int i = mObjects.size() - 1; i >= 0; i--) {
                final T obj = mObjects.valueAt(i);
                if (obj != null) {
                    result.add(obj);
                }
            }
            return result;
        }

        /**
         * Reset this heavy hitter counter
         */
        @Override
        public void reset() {
            mNumInputs = 0;
            mObjects.clear();
            mFrequencies.clear();
        }

        /**
         * @return The ratio of the input to be used as the validation data, Float.NaN means no
         * validation is needed.
         */
        public float getRequiredValidationInputRatio() {
            return 0.5f;
        }
    }
}
