/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.perftest;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Provides a benchmark framework.
 *
 * Example usage:
 * // Executes the code while keepRunning returning true.
 *
 * public void sampleMethod() {
 *     BenchmarkState state = new BenchmarkState();
 *
 *     int[] src = new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };
 *     while (state.keepRunning()) {
 *        int[] dest = new int[src.length];
 *        System.arraycopy(src, 0, dest, 0, src.length);
 *     }
 *
 *     System.out.println(state.summaryLine());
 * }
 */
public class BenchmarkState {
  private static final int NOT_STARTED = 1;  // The benchmark has not started yet.
  private static final int RUNNING = 2;  // The benchmark is running.
  private static final int FINISHED = 3;  // The benchmark has stopped.

  private int mState = NOT_STARTED;  // Current benchmark state.

  private long mNanoPreviousTime = 0;  // Previously captured System.nanoTime().
  private long mNanoFinishTime = 0;  // Finish if System.nanoTime() returns after than this value.
  private long mNanoTimeLimit = 1 * 1000 * 1000 * 1000;  // 1 sec. Default time limit.

  // Statistics. These values will be filled when the benchmark has finished.
  private long mMedian = 0;
  private double mMean = 0.0;
  private double mStandardDeviation = 0.0;

  // Individual duration in nano seconds.
  private ArrayList<Long> mResults = new ArrayList<>();

  /**
   * Calculates statistics.
   */
  private void calculateSatistics() {
      final int size = mResults.size();
      if (size <= 1) {
          throw new IllegalStateException("At least two results are necessary.");
      }

      Collections.sort(mResults);
      mMedian = size % 2 == 0 ?  (mResults.get(size / 2) + mResults.get(size / 2 + 1)) / 2 :
              mResults.get(size / 2);

      for (int i = 0; i < size; ++i) {
          mMean += mResults.get(i);
      }
      mMean /= (double)size;

      for (int i = 0; i < size; ++i) {
          final double tmp = mResults.get(i) - mMean;
          mStandardDeviation += tmp * tmp;
      }
      mStandardDeviation = Math.sqrt(mStandardDeviation / (double)(size - 1));
  }

  /**
   * Judges whether the benchmark needs more samples.
   *
   * For the usage, see class comment.
   */
  public boolean keepRunning() {
      switch (mState) {
          case NOT_STARTED:
              mNanoPreviousTime = System.nanoTime();
              mNanoFinishTime = mNanoPreviousTime + mNanoTimeLimit;
              mState = RUNNING;
              return true;
          case RUNNING:
              final long currentTime = System.nanoTime();
              mResults.add(currentTime - mNanoPreviousTime);

              // To calculate statistics, needs two or more samples.
              if (mResults.size() > 2 && currentTime > mNanoFinishTime) {
                  calculateSatistics();
                  mState = FINISHED;
                  return false;
              }

              mNanoPreviousTime = currentTime;
              return true;
          case FINISHED:
              throw new IllegalStateException("The benchmark has finished.");
          default:
              throw new IllegalStateException("The benchmark is in unknown state.");
      }
  }

  public double mean() {
      if (mState != FINISHED) {
          throw new IllegalStateException("The benchmark hasn't finished");
      }
      return mMean;
  }

  public long median() {
      if (mState != FINISHED) {
          throw new IllegalStateException("The benchmark hasn't finished");
      }
      return mMedian;
  }

  public double standardDeviation() {
      if (mState != FINISHED) {
          throw new IllegalStateException("The benchmark hasn't finished");
      }
      return mStandardDeviation;
  }

  public String summaryLine() {
      StringBuilder sb = new StringBuilder();
      sb.append("Summary: ");
      sb.append("median=" + median() + "ns, ");
      sb.append("mean=" + mean() + "ns, ");
      sb.append("sigma=" + standardDeviation() + ", ");
      sb.append("iteration=" + mResults.size());
      return sb.toString();
  }

}
