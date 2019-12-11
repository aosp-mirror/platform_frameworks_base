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

import android.location.GnssStatus;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.connectivity.GpsBatteryStats;
import android.server.location.ServerLocationProtoEnums;
import android.text.format.DateUtils;
import android.util.Base64;
import android.util.Log;
import android.util.StatsLog;
import android.util.TimeUtils;

import com.android.internal.app.IBatteryStats;
import com.android.internal.location.nano.GnssLogsProto.GnssLog;
import com.android.internal.location.nano.GnssLogsProto.PowerMetrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * GnssMetrics: Is used for logging GNSS metrics
 *
 * @hide
 */
public class GnssMetrics {

  private static final String TAG = GnssMetrics.class.getSimpleName();

  /* Constant which indicates GPS signal quality is as yet unknown */
  public static final int GPS_SIGNAL_QUALITY_UNKNOWN =
          ServerLocationProtoEnums.GPS_SIGNAL_QUALITY_UNKNOWN; // -1

  /* Constant which indicates GPS signal quality is poor */
  public static final int GPS_SIGNAL_QUALITY_POOR =
      ServerLocationProtoEnums.GPS_SIGNAL_QUALITY_POOR; // 0

  /* Constant which indicates GPS signal quality is good */
  public static final int GPS_SIGNAL_QUALITY_GOOD =
      ServerLocationProtoEnums.GPS_SIGNAL_QUALITY_GOOD; // 1

  /* Number of GPS signal quality levels */
  public static final int NUM_GPS_SIGNAL_QUALITY_LEVELS = GPS_SIGNAL_QUALITY_GOOD + 1;

  /** Default time between location fixes (in millisecs) */
  private static final int DEFAULT_TIME_BETWEEN_FIXES_MILLISECS = 1000;

  /** The number of hertz in one MHz */
  private static final double HZ_PER_MHZ = 1e6;

  /* The time since boot when logging started */
  private String logStartInElapsedRealTime;

  /* GNSS power metrics */
  private GnssPowerMetrics mGnssPowerMetrics;

  /** Frequency range of GPS L5, Galileo E5a, QZSS J5 frequency band */
  private static final double L5_CARRIER_FREQ_RANGE_LOW_HZ = 1164 * HZ_PER_MHZ;
  private static final double L5_CARRIER_FREQ_RANGE_HIGH_HZ = 1189 * HZ_PER_MHZ;

  /**
     * A boolean array indicating whether the constellation types have been used in fix.
     */
    private boolean[] mConstellationTypes;

  /** Constructor */
  public GnssMetrics(IBatteryStats stats) {
    mGnssPowerMetrics = new GnssPowerMetrics(stats);
    locationFailureStatistics = new Statistics();
    timeToFirstFixSecStatistics = new Statistics();
    positionAccuracyMeterStatistics = new Statistics();
    topFourAverageCn0Statistics = new Statistics();
    mTopFourAverageCn0StatisticsL5 = new Statistics();
    mNumSvStatus = 0;
    mNumL5SvStatus = 0;
    mNumSvStatusUsedInFix = 0;
    mNumL5SvStatusUsedInFix = 0;
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

  /**
  * Logs CN0 when at least 4 SVs are available
  *
  * @param cn0s
  * @param numSv
  * @param svCarrierFreqs
  */
  public void logCn0(float[] cn0s, int numSv, float[] svCarrierFreqs) {
    // Calculate L5 Cn0
    logCn0L5(numSv, cn0s, svCarrierFreqs);
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

  /* Helper function to check if a SV is L5 */
  private static boolean isL5Sv(float carrierFreq) {
    return (carrierFreq >= L5_CARRIER_FREQ_RANGE_LOW_HZ
            && carrierFreq <= L5_CARRIER_FREQ_RANGE_HIGH_HZ);
  }

  /**
   * Logs sv status data
   *
   * @param svCount
   * @param svidWithFlags
   * @param svCarrierFreqs
   */
  public void logSvStatus(int svCount, int[] svidWithFlags, float[] svCarrierFreqs) {
    boolean isL5 = false;
    // Calculate SvStatus Information
    for (int i = 0; i < svCount; i++) {
      if ((svidWithFlags[i] & GnssStatus.GNSS_SV_FLAGS_HAS_CARRIER_FREQUENCY) != 0) {
        mNumSvStatus++;
        isL5 = isL5Sv(svCarrierFreqs[i]);
        if (isL5) {
          mNumL5SvStatus++;
        }
        if ((svidWithFlags[i] & GnssStatus.GNSS_SV_FLAGS_USED_IN_FIX) != 0) {
          mNumSvStatusUsedInFix++;
          if (isL5) {
            mNumL5SvStatusUsedInFix++;
          }
        }
      }
    }
    return;
  }

  /**
   * Logs CN0 when at least 4 SVs are available L5 Only
   *
   * @param svCount
   * @param cn0s
   * @param svCarrierFreqs
   */
  private void logCn0L5(int svCount, float[] cn0s, float[] svCarrierFreqs) {
    if (svCount == 0 || cn0s == null || cn0s.length == 0 || cn0s.length < svCount
            || svCarrierFreqs == null || svCarrierFreqs.length == 0
            || svCarrierFreqs.length < svCount) {
      return;
    }
    // Create array list of all L5 satellites in report.
    ArrayList<Float> CnoL5Array = new ArrayList();
    for (int i = 0; i < svCount; i++) {
      if (isL5Sv(svCarrierFreqs[i])) {
        CnoL5Array.add(cn0s[i]);
      }
    }
    if (CnoL5Array.size() == 0 || CnoL5Array.size() < 4) {
      return;
    }
    int numSvL5 = CnoL5Array.size();
    Collections.sort(CnoL5Array);
    if (CnoL5Array.get(numSvL5 - 4) > 0.0) {
      double top4AvgCn0 = 0.0;
      for (int i = numSvL5 - 4; i < numSvL5; i++) {
        top4AvgCn0 += (double) CnoL5Array.get(i);
      }
      top4AvgCn0 /= 4;
      mTopFourAverageCn0StatisticsL5.addItem(top4AvgCn0);
    }
    return;
  }


    /**
     * Logs that a constellation type has been observed.
     */
    public void logConstellationType(int constellationType) {
        if (constellationType >= mConstellationTypes.length) {
            Log.e(TAG, "Constellation type " + constellationType + " is not valid.");
            return;
        }
        mConstellationTypes[constellationType] = true;
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
    if (mNumSvStatus > 0) {
      msg.numSvStatusProcessed = mNumSvStatus;
    }
    if (mNumL5SvStatus > 0) {
      msg.numL5SvStatusProcessed = mNumL5SvStatus;
    }
    if (mNumSvStatusUsedInFix > 0) {
      msg.numSvStatusUsedInFix = mNumSvStatusUsedInFix;
    }
    if (mNumL5SvStatusUsedInFix > 0) {
      msg.numL5SvStatusUsedInFix = mNumL5SvStatusUsedInFix;
    }
    if (mTopFourAverageCn0StatisticsL5.getCount() > 0) {
      msg.numL5TopFourAverageCn0Processed = mTopFourAverageCn0StatisticsL5.getCount();
      msg.meanL5TopFourAverageCn0DbHz = mTopFourAverageCn0StatisticsL5.getMean();
      msg.standardDeviationL5TopFourAverageCn0DbHz =
              mTopFourAverageCn0StatisticsL5.getStandardDeviation();
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
    s.append("  Total number of sv status messages processed: ").append(
            mNumSvStatus).append("\n");
    s.append("  Total number of L5 sv status messages processed: ").append(
            mNumL5SvStatus).append("\n");
    s.append("  Total number of sv status messages processed, where sv is used in fix: ").append(
            mNumSvStatusUsedInFix).append("\n");
    s.append("  Total number of L5 sv status messages processed, where sv is used in fix: ").append(
            mNumL5SvStatusUsedInFix).append("\n");
    s.append("  Number of L5 CN0 reports: ").append(
            mTopFourAverageCn0StatisticsL5.getCount()).append("\n");
    if (mTopFourAverageCn0StatisticsL5.getCount() > 0) {
      s.append("  L5 Top 4 Avg CN0 mean (dB-Hz): ").append(
              mTopFourAverageCn0StatisticsL5.getMean()).append("\n");
      s.append("  L5 Top 4 Avg CN0 standard deviation (dB-Hz): ").append(
              mTopFourAverageCn0StatisticsL5.getStandardDeviation()).append("\n");
    }
        s.append("  Used-in-fix constellation types: ");
        for (int i = 0; i < mConstellationTypes.length; i++) {
            if (mConstellationTypes[i]) {
                s.append(GnssStatus.constellationTypeToString(i)).append(" ");
            }
        }
        s.append("\n");
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

  /** Top 4 average CN0 statistics L5 */
  private Statistics mTopFourAverageCn0StatisticsL5;

  /** Total number of sv status messages processed */
  private int mNumSvStatus;

  /** Total number of L5 sv status messages processed */
  private int mNumL5SvStatus;

  /** Total number of sv status messages processed, where sv is used in fix */
  private int mNumSvStatusUsedInFix;

  /** Total number of L5 sv status messages processed, where sv is used in fix */
  private int mNumL5SvStatusUsedInFix;

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
    mTopFourAverageCn0StatisticsL5.reset();
    mNumSvStatus = 0;
    mNumL5SvStatus = 0;
    mNumSvStatusUsedInFix = 0;
    mNumL5SvStatusUsedInFix = 0;
        resetConstellationTypes();
    return;
  }

    /** Resets {@link #mConstellationTypes} as an all-false boolean array. */
    public void resetConstellationTypes() {
        mConstellationTypes = new boolean[GnssStatus.CONSTELLATION_COUNT];
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

    /* Last reported signal quality bin (based on Top Four Average CN0) */
    private int mLastSignalLevel;

    public GnssPowerMetrics(IBatteryStats stats) {
      mBatteryStats = stats;
      // Used to initialize the variable to a very small value (unachievable in practice) so that
      // the first CNO report will trigger an update to BatteryStats
      mLastAverageCn0 = -100.0;
      mLastSignalLevel = GPS_SIGNAL_QUALITY_UNKNOWN;
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
      int signalLevel = getSignalLevel(avgCn0);
      if (signalLevel != mLastSignalLevel) {
        StatsLog.write(StatsLog.GPS_SIGNAL_QUALITY_CHANGED, signalLevel);
        mLastSignalLevel = signalLevel;
      }
      try {
        mBatteryStats.noteGpsSignalQuality(signalLevel);
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
