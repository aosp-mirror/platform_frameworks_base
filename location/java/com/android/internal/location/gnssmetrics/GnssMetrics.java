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
import android.os.connectivity.GpsBatteryStats;
import android.os.SystemProperties;

import android.text.format.DateUtils;
import android.util.Base64;
import android.util.Log;
import android.util.TimeUtils;

import java.util.Arrays;

import com.android.internal.app.IBatteryStats;
import com.android.internal.location.nano.GnssLogsProto.GnssLog;
import com.android.internal.location.nano.GnssLogsProto.PowerMetrics;

/**
 * GnssMetrics: Is used for logging GNSS metrics
 * @hide
 */
public class GnssMetrics {

  private static final String TAG = GnssMetrics.class.getSimpleName();

  /* Constant which indicates GPS signal quality is poor */
  public static final int GPS_SIGNAL_QUALITY_POOR = 0;

  /* Constant which indicates GPS signal quality is good */
  public static final int GPS_SIGNAL_QUALITY_GOOD = 1;

  /* Number of GPS signal quality levels */
  public static final int NUM_GPS_SIGNAL_QUALITY_LEVELS = GPS_SIGNAL_QUALITY_GOOD + 1;

  /** Default time between location fixes (in millisecs) */
  private static final int DEFAULT_TIME_BETWEEN_FIXES_MILLISECS = 1000;

  /* The time since boot when logging started */
  private String logStartInElapsedRealTime;

  /* GNSS power metrics */
  private GnssPowerMetrics mGnssPowerMetrics;

  /** Constructor */
  public GnssMetrics(IBatteryStats stats) {
    mGnssPowerMetrics = new GnssPowerMetrics(stats);
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
    if (numSv == 0 || cn0s == null || cn0s.length == 0 || cn0s.length < numSv) {
      if (numSv == 0) {
         mGnssPowerMetrics.reportSignalQuality(null, 0);
      }
      return;
    }
    float[] cn0Array = Arrays.copyOf(cn0s, numSv);
    Arrays.sort(cn0Array);
    mGnssPowerMetrics.reportSignalQuality(cn0Array, numSv);
    if (numSv < 4) {
      return;
    }
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
    msg.powerMetrics = mGnssPowerMetrics.buildProto();
    msg.hardwareRevision = SystemProperties.get("ro.boot.revision", "");
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
    GpsBatteryStats stats = mGnssPowerMetrics.getGpsBatteryStats();
    if (stats != null) {
      s.append("Power Metrics").append("\n");
      s.append("  Time on battery (min): "
          + stats.getLoggingDurationMs() / ((double) DateUtils.MINUTE_IN_MILLIS)).append("\n");
      long[] t = stats.getTimeInGpsSignalQualityLevel();
      if (t != null && t.length == NUM_GPS_SIGNAL_QUALITY_LEVELS) {
        s.append("  Amount of time (while on battery) Top 4 Avg CN0 > " +
            Double.toString(GnssPowerMetrics.POOR_TOP_FOUR_AVG_CN0_THRESHOLD_DB_HZ) +
            " dB-Hz (min): ").append(t[1] / ((double) DateUtils.MINUTE_IN_MILLIS)).append("\n");
        s.append("  Amount of time (while on battery) Top 4 Avg CN0 <= " +
            Double.toString(GnssPowerMetrics.POOR_TOP_FOUR_AVG_CN0_THRESHOLD_DB_HZ) +
            " dB-Hz (min): ").append(t[0] / ((double) DateUtils.MINUTE_IN_MILLIS)).append("\n");
      }
      s.append("  Energy consumed while on battery (mAh): ").append(
          stats.getEnergyConsumedMaMs() / ((double) DateUtils.HOUR_IN_MILLIS)).append("\n");
    }
    s.append("Hardware Version: " + SystemProperties.get("ro.boot.revision", "")).append("\n");
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

  /* Class for handling GNSS power related metrics */
  private class GnssPowerMetrics {

    /* Threshold for Top Four Average CN0 below which GNSS signal quality is declared poor */
    public static final double POOR_TOP_FOUR_AVG_CN0_THRESHOLD_DB_HZ = 20.0;

    /* Minimum change in Top Four Average CN0 needed to trigger a report */
    private static final double REPORTING_THRESHOLD_DB_HZ = 1.0;

    /* BatteryStats API */
    private final IBatteryStats mBatteryStats;

    /* Last reported Top Four Average CN0 */
    private double mLastAverageCn0;

    public GnssPowerMetrics(IBatteryStats stats) {
      mBatteryStats = stats;
      // Used to initialize the variable to a very small value (unachievable in practice) so that
      // the first CNO report will trigger an update to BatteryStats
      mLastAverageCn0 = -100.0;
    }

    /**
     * Builds power metrics proto buf. This is included in the gnss proto buf.
     * @return PowerMetrics
     */
    public PowerMetrics buildProto() {
      PowerMetrics p = new PowerMetrics();
      GpsBatteryStats stats = mGnssPowerMetrics.getGpsBatteryStats();
      if (stats != null) {
        p.loggingDurationMs = stats.getLoggingDurationMs();
        p.energyConsumedMah = stats.getEnergyConsumedMaMs() / ((double) DateUtils.HOUR_IN_MILLIS);
        long[] t = stats.getTimeInGpsSignalQualityLevel();
        p.timeInSignalQualityLevelMs = new long[t.length];
        for (int i = 0; i < t.length; i++) {
          p.timeInSignalQualityLevelMs[i] = t[i];
        }
      }
      return p;
    }

    /**
     * Returns the GPS power stats
     * @return GpsBatteryStats
     */
    public GpsBatteryStats getGpsBatteryStats() {
      try {
        return mBatteryStats.getGpsBatteryStats();
      } catch (Exception e) {
        Log.w(TAG, "Exception", e);
        return null;
      }
    }

    /**
     * Reports signal quality to BatteryStats. Signal quality is based on Top four average CN0. If
     * the number of SVs seen is less than 4, then signal quality is the average CN0.
     * Changes are reported only if the average CN0 changes by more than REPORTING_THRESHOLD_DB_HZ.
     */
    public void reportSignalQuality(float[] ascendingCN0Array, int numSv) {
      double avgCn0 = 0.0;
      if (numSv > 0) {
        for (int i = Math.max(0, numSv - 4); i < numSv; i++) {
          avgCn0 += (double) ascendingCN0Array[i];
        }
        avgCn0 /= Math.min(numSv, 4);
      }
      if (Math.abs(avgCn0 - mLastAverageCn0) < REPORTING_THRESHOLD_DB_HZ) {
        return;
      }
      try {
        mBatteryStats.noteGpsSignalQuality(getSignalLevel(avgCn0));
        mLastAverageCn0 = avgCn0;
      } catch (Exception e) {
        Log.w(TAG, "Exception", e);
      }
      return;
    }

    /**
     * Obtains signal level based on CN0
     */
    private int getSignalLevel(double cn0) {
      if (cn0 > POOR_TOP_FOUR_AVG_CN0_THRESHOLD_DB_HZ) {
        return GnssMetrics.GPS_SIGNAL_QUALITY_GOOD;
      }
      return GnssMetrics.GPS_SIGNAL_QUALITY_POOR;
    }
  }
}
