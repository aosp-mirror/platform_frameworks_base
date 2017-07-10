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

package com.android.internal.location.gnssmetrics;

import android.os.SystemClock;

import android.util.Base64;
import android.util.TimeUtils;

import java.util.Arrays;

import com.android.internal.location.nano.GnssLogsProto.GnssLog;

/**
 * GnssMetrics: Is used for logging GNSS metrics
 * @hide
 */
public class GnssMetrics {

  /** Default time between location fixes (in millisecs) */
  private static final int DEFAULT_TIME_BETWEEN_FIXES_MILLISECS = 1000;

  /* The time since boot when logging started */
  private String logStartInElapsedRealTime;

  /** Constructor */
  public GnssMetrics() {
    locationFailureStatistics = new Statistics();
    timeToFirstFixSecStatistics = new Statistics();
    positionAccuracyMeterStatistics = new Statistics();
    topFourAverageCn0Statistics = new Statistics();
    reset();
  }

  /**
   * Logs the status of a location report received from the HAL
   *
   * @param isSuccessful
   */
  public void logReceivedLocationStatus(boolean isSuccessful) {
    if (!isSuccessful) {
      locationFailureStatistics.addItem(1.0);
      return;
    }
    locationFailureStatistics.addItem(0.0);
    return;
  }

  /**
   * Logs missed reports
   *
   * @param desiredTimeBetweenFixesMilliSeconds
   * @param actualTimeBetweenFixesMilliSeconds
   */
  public void logMissedReports(int desiredTimeBetweenFixesMilliSeconds,
      int actualTimeBetweenFixesMilliSeconds) {
    int numReportMissed = (actualTimeBetweenFixesMilliSeconds /
        Math.max(DEFAULT_TIME_BETWEEN_FIXES_MILLISECS, desiredTimeBetweenFixesMilliSeconds)) - 1;
    if (numReportMissed > 0) {
      for (int i = 0; i < numReportMissed; i++) {
        locationFailureStatistics.addItem(1.0);
      }
    }
    return;
  }

  /**
   * Logs time to first fix
   *
   * @param timeToFirstFixMilliSeconds
   */
  public void logTimeToFirstFixMilliSecs(int timeToFirstFixMilliSeconds) {
    timeToFirstFixSecStatistics.addItem((double) (timeToFirstFixMilliSeconds/1000));
    return;
  }

  /**
   * Logs position accuracy
   *
   * @param positionAccuracyMeters
   */
  public void logPositionAccuracyMeters(float positionAccuracyMeters) {
    positionAccuracyMeterStatistics.addItem((double) positionAccuracyMeters);
    return;
  }

  /*
  * Logs CN0 when at least 4 SVs are available
  *
  */
  public void logCn0(float[] cn0s, int numSv) {
    if (numSv < 4) {
      return;
    }
    float[] cn0Array = Arrays.copyOf(cn0s, numSv);
    Arrays.sort(cn0Array);
    if (cn0Array[numSv - 4] > 0.0) {
      double top4AvgCn0 = 0.0;
      for (int i = numSv - 4; i < numSv; i++) {
        top4AvgCn0 += (double) cn0Array[i];
      }
      top4AvgCn0 /= 4;
      topFourAverageCn0Statistics.addItem(top4AvgCn0);
    }
    return;
  }

  /**
   * Dumps GNSS metrics as a proto string
   * @return
   */
  public String dumpGnssMetricsAsProtoString() {
    GnssLog msg = new GnssLog();
    if (locationFailureStatistics.getCount() > 0) {
      msg.numLocationReportProcessed = locationFailureStatistics.getCount();
      msg.percentageLocationFailure = (int) (100.0 * locationFailureStatistics.getMean());
    }
    if (timeToFirstFixSecStatistics.getCount() > 0) {
      msg.numTimeToFirstFixProcessed = timeToFirstFixSecStatistics.getCount();
      msg.meanTimeToFirstFixSecs = (int) timeToFirstFixSecStatistics.getMean();
      msg.standardDeviationTimeToFirstFixSecs
          = (int) timeToFirstFixSecStatistics.getStandardDeviation();
    }
    if (positionAccuracyMeterStatistics.getCount() > 0) {
      msg.numPositionAccuracyProcessed = positionAccuracyMeterStatistics.getCount();
      msg.meanPositionAccuracyMeters = (int) positionAccuracyMeterStatistics.getMean();
      msg.standardDeviationPositionAccuracyMeters
          = (int) positionAccuracyMeterStatistics.getStandardDeviation();
    }
    if (topFourAverageCn0Statistics.getCount() > 0) {
      msg.numTopFourAverageCn0Processed = topFourAverageCn0Statistics.getCount();
      msg.meanTopFourAverageCn0DbHz = topFourAverageCn0Statistics.getMean();
      msg.standardDeviationTopFourAverageCn0DbHz
          = topFourAverageCn0Statistics.getStandardDeviation();
    }
    String s = Base64.encodeToString(GnssLog.toByteArray(msg), Base64.DEFAULT);
    reset();
    return s;
  }

  /**
   * Dumps GNSS Metrics as text
   *
   * @return GNSS Metrics
   */
  public String dumpGnssMetricsAsText() {
    StringBuilder s = new StringBuilder();
    s.append("GNSS_KPI_START").append('\n');
    s.append("  KPI logging start time: ").append(logStartInElapsedRealTime).append("\n");
    s.append("  KPI logging end time: ");
    TimeUtils.formatDuration(SystemClock.elapsedRealtimeNanos() / 1000000L, s);
    s.append("\n");
    s.append("  Number of location reports: ").append(
        locationFailureStatistics.getCount()).append("\n");
    if (locationFailureStatistics.getCount() > 0) {
      s.append("  Percentage location failure: ").append(
          100.0 * locationFailureStatistics.getMean()).append("\n");
    }
    s.append("  Number of TTFF reports: ").append(
        timeToFirstFixSecStatistics.getCount()).append("\n");
    if (timeToFirstFixSecStatistics.getCount() > 0) {
      s.append("  TTFF mean (sec): ").append(timeToFirstFixSecStatistics.getMean()).append("\n");
      s.append("  TTFF standard deviation (sec): ").append(
          timeToFirstFixSecStatistics.getStandardDeviation()).append("\n");
    }
    s.append("  Number of position accuracy reports: ").append(
        positionAccuracyMeterStatistics.getCount()).append("\n");
    if (positionAccuracyMeterStatistics.getCount() > 0) {
      s.append("  Position accuracy mean (m): ").append(
          positionAccuracyMeterStatistics.getMean()).append("\n");
      s.append("  Position accuracy standard deviation (m): ").append(
          positionAccuracyMeterStatistics.getStandardDeviation()).append("\n");
    }
    s.append("  Number of CN0 reports: ").append(
        topFourAverageCn0Statistics.getCount()).append("\n");
    if (topFourAverageCn0Statistics.getCount() > 0) {
      s.append("  Top 4 Avg CN0 mean (dB-Hz): ").append(
          topFourAverageCn0Statistics.getMean()).append("\n");
      s.append("  Top 4 Avg CN0 standard deviation (dB-Hz): ").append(
          topFourAverageCn0Statistics.getStandardDeviation()).append("\n");
    }
    s.append("GNSS_KPI_END").append("\n");
    return s.toString();
  }

   /** Class for storing statistics */
  private class Statistics {

    /** Resets statistics */
    public void reset() {
      count = 0;
      sum = 0.0;
      sumSquare = 0.0;
    }

    /** Adds an item */
    public void addItem(double item) {
      count++;
      sum += item;
      sumSquare += item * item;
    }

    /** Returns number of items added */
    public int getCount() {
      return count;
    }

    /** Returns mean */
    public double getMean() {
      return sum/count;
    }

    /** Returns standard deviation */
    public double getStandardDeviation() {
      double m = sum/count;
      m = m * m;
      double v = sumSquare/count;
      if (v > m) {
        return Math.sqrt(v - m);
      }
      return 0;
    }

    private int count;
    private double sum;
    private double sumSquare;
  }

  /** Location failure statistics */
  private Statistics locationFailureStatistics;

  /** Time to first fix statistics */
  private Statistics timeToFirstFixSecStatistics;

  /** Position accuracy statistics */
  private Statistics positionAccuracyMeterStatistics;

  /** Top 4 average CN0 statistics */
  private Statistics topFourAverageCn0Statistics;

  /**
   * Resets GNSS metrics
   */
  private void reset() {
    StringBuilder s = new StringBuilder();
    TimeUtils.formatDuration(SystemClock.elapsedRealtimeNanos() / 1000000L, s);
    logStartInElapsedRealTime = s.toString();
    locationFailureStatistics.reset();
    timeToFirstFixSecStatistics.reset();
    positionAccuracyMeterStatistics.reset();
    topFourAverageCn0Statistics.reset();
    return;
  }
}