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

import android.app.StatsManager;
import android.content.Context;
import android.location.GnssStatus;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.connectivity.GpsBatteryStats;
import android.server.location.ServerLocationProtoEnums;
import android.text.format.DateUtils;
import android.util.Base64;
import android.util.Log;
import android.util.StatsEvent;
import android.util.TimeUtils;

import com.android.internal.app.IBatteryStats;
import com.android.internal.location.nano.GnssLogsProto.GnssLog;
import com.android.internal.location.nano.GnssLogsProto.PowerMetrics;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.FrameworkStatsLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * GnssMetrics: Is used for logging GNSS metrics
 *
 * @hide
 */
public class GnssMetrics {

    private static final String TAG = GnssMetrics.class.getSimpleName();

    /* Constant which indicates GPS signal quality is as yet unknown */
    private static final int GPS_SIGNAL_QUALITY_UNKNOWN =
            ServerLocationProtoEnums.GPS_SIGNAL_QUALITY_UNKNOWN; // -1

    /* Constant which indicates GPS signal quality is poor */
    private static final int GPS_SIGNAL_QUALITY_POOR =
            ServerLocationProtoEnums.GPS_SIGNAL_QUALITY_POOR; // 0

    /* Constant which indicates GPS signal quality is good */
    private static final int GPS_SIGNAL_QUALITY_GOOD =
            ServerLocationProtoEnums.GPS_SIGNAL_QUALITY_GOOD; // 1

    /* Number of GPS signal quality levels */
    public static final int NUM_GPS_SIGNAL_QUALITY_LEVELS = GPS_SIGNAL_QUALITY_GOOD + 1;

    /** Default time between location fixes (in millisecs) */
    private static final int DEFAULT_TIME_BETWEEN_FIXES_MILLISECS = 1000;

    /* The time since boot when logging started */
    private String mLogStartInElapsedRealTime;

    /** The number of hertz in one MHz */
    private static final double HZ_PER_MHZ = 1e6;

    /* GNSS power metrics */
    private GnssPowerMetrics mGnssPowerMetrics;

    /** Frequency range of GPS L5, Galileo E5a, QZSS J5 frequency band */
    private static final double L5_CARRIER_FREQ_RANGE_LOW_HZ = 1164 * HZ_PER_MHZ;
    private static final double L5_CARRIER_FREQ_RANGE_HIGH_HZ = 1189 * HZ_PER_MHZ;

    /* A boolean array indicating whether the constellation types have been used in fix. */
    private boolean[] mConstellationTypes;
    /** Location failure statistics */
    private Statistics mLocationFailureStatistics;
    /** Time to first fix statistics */
    private Statistics mTimeToFirstFixSecStatistics;
    /** Position accuracy statistics */
    private Statistics mPositionAccuracyMeterStatistics;
    /** Top 4 average CN0 statistics */
    private Statistics mTopFourAverageCn0Statistics;
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

    /* Statsd Logging Variables Section Start */
    /** Location failure reports since boot used for statsd logging */
    private Statistics mLocationFailureReportsStatistics;
    /** Time to first fix milli-seconds since boot used for statsd logging */
    private Statistics mTimeToFirstFixMilliSReportsStatistics;
    /** Position accuracy meters since boot used for statsd logging  */
    private Statistics mPositionAccuracyMetersReportsStatistics;
    /** Top 4 average CN0 (db-mHz) since boot used for statsd logging  */
    private Statistics mTopFourAverageCn0DbmHzReportsStatistics;
    /** Top 4 average CN0 (db-mHz) L5 since boot used for statsd logging  */
    private Statistics mL5TopFourAverageCn0DbmHzReportsStatistics;
    /** Total number of sv status reports processed since boot used for statsd logging  */
    private long mSvStatusReports;
    /** Total number of L5 sv status reports processed since boot used for statsd logging  */
    private long mL5SvStatusReports;
    /** Total number of sv status reports processed, where sv is used in fix since boot used for
     * statsd logging  */
    private long mSvStatusReportsUsedInFix;
    /** Total number of L5 sv status reports processed, where sv is used in fix since boot used for
     * statsd logging  */
    private long mL5SvStatusReportsUsedInFix;
    /** Stats manager service for reporting atoms */
    private StatsManager mStatsManager;
    /* Statds Logging Variables Section End */

    public GnssMetrics(Context context, IBatteryStats stats) {
        mGnssPowerMetrics = new GnssPowerMetrics(stats);
        mLocationFailureStatistics = new Statistics();
        mTimeToFirstFixSecStatistics = new Statistics();
        mPositionAccuracyMeterStatistics = new Statistics();
        mTopFourAverageCn0Statistics = new Statistics();
        mTopFourAverageCn0StatisticsL5 = new Statistics();
        reset();
        mLocationFailureReportsStatistics = new Statistics();
        mTimeToFirstFixMilliSReportsStatistics = new Statistics();
        mPositionAccuracyMetersReportsStatistics = new Statistics();
        mTopFourAverageCn0DbmHzReportsStatistics = new Statistics();
        mL5TopFourAverageCn0DbmHzReportsStatistics = new Statistics();
        mStatsManager = (StatsManager) context.getSystemService(Context.STATS_MANAGER);
        registerGnssStats();
    }

    /**
     * Logs the status of a location report received from the HAL
     */
    public void logReceivedLocationStatus(boolean isSuccessful) {
        if (!isSuccessful) {
            mLocationFailureStatistics.addItem(1.0);
            mLocationFailureReportsStatistics.addItem(1.0);
            return;
        }
        mLocationFailureStatistics.addItem(0.0);
        mLocationFailureReportsStatistics.addItem(0.0);
    }

    /**
     * Logs missed reports
     */
    public void logMissedReports(int desiredTimeBetweenFixesMilliSeconds,
            int actualTimeBetweenFixesMilliSeconds) {
        int numReportMissed = (actualTimeBetweenFixesMilliSeconds / Math.max(
                DEFAULT_TIME_BETWEEN_FIXES_MILLISECS, desiredTimeBetweenFixesMilliSeconds)) - 1;
        if (numReportMissed > 0) {
            for (int i = 0; i < numReportMissed; i++) {
                mLocationFailureStatistics.addItem(1.0);
                mLocationFailureReportsStatistics.addItem(1.0);
            }
        }
    }

    /**
     * Logs time to first fix
     */
    public void logTimeToFirstFixMilliSecs(int timeToFirstFixMilliSeconds) {
        mTimeToFirstFixSecStatistics.addItem((double) (timeToFirstFixMilliSeconds / 1000));
        mTimeToFirstFixMilliSReportsStatistics.addItem(timeToFirstFixMilliSeconds);
    }

    /**
     * Logs position accuracy
     */
    public void logPositionAccuracyMeters(float positionAccuracyMeters) {
        mPositionAccuracyMeterStatistics.addItem((double) positionAccuracyMeters);
        mPositionAccuracyMetersReportsStatistics.addItem(positionAccuracyMeters);
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
            mTopFourAverageCn0Statistics.addItem(top4AvgCn0);
            // Convert to mHz for accuracy
            mTopFourAverageCn0DbmHzReportsStatistics.addItem(top4AvgCn0 * 1000);
        }
    }
    /* Helper function to check if a SV is L5 */
    private static boolean isL5Sv(float carrierFreq) {
        return (carrierFreq >= L5_CARRIER_FREQ_RANGE_LOW_HZ
                && carrierFreq <= L5_CARRIER_FREQ_RANGE_HIGH_HZ);
    }

    /**
    * Logs sv status data
    */
    public void logSvStatus(GnssStatus status) {
        boolean isL5 = false;
        // Calculate SvStatus Information
        for (int i = 0; i < status.getSatelliteCount(); i++) {
            if (status.hasCarrierFrequencyHz(i)) {
                mNumSvStatus++;
                mSvStatusReports++;
                isL5 = isL5Sv(status.getCarrierFrequencyHz(i));
                if (isL5) {
                    mNumL5SvStatus++;
                    mL5SvStatusReports++;
                }
                if (status.usedInFix(i)) {
                    mNumSvStatusUsedInFix++;
                    mSvStatusReportsUsedInFix++;
                    if (isL5) {
                        mNumL5SvStatusUsedInFix++;
                        mL5SvStatusReportsUsedInFix++;
                    }
                }
            }
        }
    }

    /**
    * Logs CN0 when at least 4 SVs are available L5 Only
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
            // Convert to mHz for accuracy
            mL5TopFourAverageCn0DbmHzReportsStatistics.addItem(top4AvgCn0 * 1000);
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
     */
    public String dumpGnssMetricsAsProtoString() {
        GnssLog msg = new GnssLog();
        if (mLocationFailureStatistics.getCount() > 0) {
            msg.numLocationReportProcessed = mLocationFailureStatistics.getCount();
            msg.percentageLocationFailure = (int) (100.0 * mLocationFailureStatistics.getMean());
        }
        if (mTimeToFirstFixSecStatistics.getCount() > 0) {
            msg.numTimeToFirstFixProcessed = mTimeToFirstFixSecStatistics.getCount();
            msg.meanTimeToFirstFixSecs = (int) mTimeToFirstFixSecStatistics.getMean();
            msg.standardDeviationTimeToFirstFixSecs =
                    (int) mTimeToFirstFixSecStatistics.getStandardDeviation();
        }
        if (mPositionAccuracyMeterStatistics.getCount() > 0) {
            msg.numPositionAccuracyProcessed = mPositionAccuracyMeterStatistics.getCount();
            msg.meanPositionAccuracyMeters = (int) mPositionAccuracyMeterStatistics.getMean();
            msg.standardDeviationPositionAccuracyMeters =
                    (int) mPositionAccuracyMeterStatistics.getStandardDeviation();
        }
        if (mTopFourAverageCn0Statistics.getCount() > 0) {
            msg.numTopFourAverageCn0Processed = mTopFourAverageCn0Statistics.getCount();
            msg.meanTopFourAverageCn0DbHz = mTopFourAverageCn0Statistics.getMean();
            msg.standardDeviationTopFourAverageCn0DbHz =
                    mTopFourAverageCn0Statistics.getStandardDeviation();
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
        s.append("  KPI logging start time: ").append(mLogStartInElapsedRealTime).append("\n");
        s.append("  KPI logging end time: ");
        TimeUtils.formatDuration(SystemClock.elapsedRealtimeNanos() / 1000000L, s);
        s.append("\n");
        s.append("  Number of location reports: ").append(
                mLocationFailureStatistics.getCount()).append("\n");
        if (mLocationFailureStatistics.getCount() > 0) {
            s.append("  Percentage location failure: ").append(
                    100.0 * mLocationFailureStatistics.getMean()).append("\n");
        }
        s.append("  Number of TTFF reports: ").append(
                mTimeToFirstFixSecStatistics.getCount()).append("\n");
        if (mTimeToFirstFixSecStatistics.getCount() > 0) {
            s.append("  TTFF mean (sec): ").append(mTimeToFirstFixSecStatistics.getMean()).append(
                    "\n");
            s.append("  TTFF standard deviation (sec): ").append(
                    mTimeToFirstFixSecStatistics.getStandardDeviation()).append("\n");
        }
        s.append("  Number of position accuracy reports: ").append(
                mPositionAccuracyMeterStatistics.getCount()).append("\n");
        if (mPositionAccuracyMeterStatistics.getCount() > 0) {
            s.append("  Position accuracy mean (m): ").append(
                    mPositionAccuracyMeterStatistics.getMean()).append("\n");
            s.append("  Position accuracy standard deviation (m): ").append(
                    mPositionAccuracyMeterStatistics.getStandardDeviation()).append("\n");
        }
        s.append("  Number of CN0 reports: ").append(
                mTopFourAverageCn0Statistics.getCount()).append("\n");
        if (mTopFourAverageCn0Statistics.getCount() > 0) {
            s.append("  Top 4 Avg CN0 mean (dB-Hz): ").append(
                    mTopFourAverageCn0Statistics.getMean()).append("\n");
            s.append("  Top 4 Avg CN0 standard deviation (dB-Hz): ").append(
                    mTopFourAverageCn0Statistics.getStandardDeviation()).append("\n");
        }
        s.append("  Total number of sv status messages processed: ").append(
                mNumSvStatus).append("\n");
        s.append("  Total number of L5 sv status messages processed: ").append(
                mNumL5SvStatus).append("\n");
        s.append("  Total number of sv status messages processed, "
                + "where sv is used in fix: ").append(
                mNumSvStatusUsedInFix).append("\n");
        s.append("  Total number of L5 sv status messages processed, "
                + "where sv is used in fix: ").append(
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
            s.append("  Time on battery (min): ").append(
                    stats.getLoggingDurationMs() / ((double) DateUtils.MINUTE_IN_MILLIS)).append(
                    "\n");
            long[] t = stats.getTimeInGpsSignalQualityLevel();
            if (t != null && t.length == NUM_GPS_SIGNAL_QUALITY_LEVELS) {
                s.append("  Amount of time (while on battery) Top 4 Avg CN0 > "
                        + GnssPowerMetrics.POOR_TOP_FOUR_AVG_CN0_THRESHOLD_DB_HZ
                        + " dB-Hz (min): ").append(
                        t[1] / ((double) DateUtils.MINUTE_IN_MILLIS)).append("\n");
                s.append("  Amount of time (while on battery) Top 4 Avg CN0 <= "
                        + GnssPowerMetrics.POOR_TOP_FOUR_AVG_CN0_THRESHOLD_DB_HZ
                        + " dB-Hz (min): ").append(
                        t[0] / ((double) DateUtils.MINUTE_IN_MILLIS)).append("\n");
            }
            s.append("  Energy consumed while on battery (mAh): ").append(
                    stats.getEnergyConsumedMaMs() / ((double) DateUtils.HOUR_IN_MILLIS)).append(
                    "\n");
        }
        s.append("Hardware Version: ").append(SystemProperties.get("ro.boot.revision", "")).append(
                "\n");
        return s.toString();
    }

    private void reset() {
        StringBuilder s = new StringBuilder();
        TimeUtils.formatDuration(SystemClock.elapsedRealtimeNanos() / 1000000L, s);
        mLogStartInElapsedRealTime = s.toString();
        mLocationFailureStatistics.reset();
        mTimeToFirstFixSecStatistics.reset();
        mPositionAccuracyMeterStatistics.reset();
        mTopFourAverageCn0Statistics.reset();
        resetConstellationTypes();
        mTopFourAverageCn0StatisticsL5.reset();
        mNumSvStatus = 0;
        mNumL5SvStatus = 0;
        mNumSvStatusUsedInFix = 0;
        mNumL5SvStatusUsedInFix = 0;
    }

    /** Resets {@link #mConstellationTypes} as an all-false boolean array. */
    public void resetConstellationTypes() {
        mConstellationTypes = new boolean[GnssStatus.CONSTELLATION_COUNT];
    }

    /** Thread-safe class for storing statistics */
    private static class Statistics {

        private int mCount;
        private double mSum;
        private double mSumSquare;
        private long mLongSum;

        /** Resets statistics */
        public synchronized void reset() {
            mCount = 0;
            mSum = 0.0;
            mSumSquare = 0.0;
            mLongSum = 0;
        }

        /** Adds an item */
        public synchronized void addItem(double item) {
            mCount++;
            mSum += item;
            mSumSquare += item * item;
            mLongSum += item;
        }

        /** Returns number of items added */
        public synchronized int getCount() {
            return mCount;
        }

        /** Returns mean */
        public synchronized double getMean() {
            return mSum / mCount;
        }

        /** Returns standard deviation */
        public synchronized double getStandardDeviation() {
            double m = mSum / mCount;
            m = m * m;
            double v = mSumSquare / mCount;
            if (v > m) {
                return Math.sqrt(v - m);
            }
            return 0;
        }

        /** Returns long sum */
        public synchronized long getLongSum() {
            return mLongSum;
        }
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

        private GnssPowerMetrics(IBatteryStats stats) {
            mBatteryStats = stats;
            // Used to initialize the variable to a very small value (unachievable in practice)
          // so that
            // the first CNO report will trigger an update to BatteryStats
            mLastAverageCn0 = -100.0;
            mLastSignalLevel = GPS_SIGNAL_QUALITY_UNKNOWN;
        }

        /**
         * Builds power metrics proto buf. This is included in the gnss proto buf.
         *
         * @return PowerMetrics
         */
        public PowerMetrics buildProto() {
            PowerMetrics p = new PowerMetrics();
            GpsBatteryStats stats = mGnssPowerMetrics.getGpsBatteryStats();
            if (stats != null) {
                p.loggingDurationMs = stats.getLoggingDurationMs();
                p.energyConsumedMah =
                        stats.getEnergyConsumedMaMs() / ((double) DateUtils.HOUR_IN_MILLIS);
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
         *
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
         * Reports signal quality to BatteryStats. Signal quality is based on Top four average CN0.
         * If
         * the number of SVs seen is less than 4, then signal quality is the average CN0.
         * Changes are reported only if the average CN0 changes by more than
         * REPORTING_THRESHOLD_DB_HZ.
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
                FrameworkStatsLog.write(FrameworkStatsLog.GPS_SIGNAL_QUALITY_CHANGED, signalLevel);
                mLastSignalLevel = signalLevel;
            }
            try {
                mBatteryStats.noteGpsSignalQuality(signalLevel);
                mLastAverageCn0 = avgCn0;
            } catch (Exception e) {
                Log.w(TAG, "Exception", e);
            }
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

    private void registerGnssStats() {
        StatsPullAtomCallbackImpl pullAtomCallback = new StatsPullAtomCallbackImpl();
        mStatsManager.setPullAtomCallback(
                FrameworkStatsLog.GNSS_STATS,
                null, // use default PullAtomMetadata values
                ConcurrentUtils.DIRECT_EXECUTOR, pullAtomCallback);
    }

    /**
     * Stats Pull Atom Callback
     * Calls the pull method to fill out gnss stats
     */
    private class StatsPullAtomCallbackImpl implements StatsManager.StatsPullAtomCallback {
        @Override
        public int onPullAtom(int atomTag, List<StatsEvent> data) {
            if (atomTag != FrameworkStatsLog.GNSS_STATS) {
                throw new UnsupportedOperationException("Unknown tagId = " + atomTag);
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeLong(mLocationFailureReportsStatistics.getCount())
                    .writeLong(mLocationFailureReportsStatistics.getLongSum())
                    .writeLong(mTimeToFirstFixMilliSReportsStatistics.getCount())
                    .writeLong(mTimeToFirstFixMilliSReportsStatistics.getLongSum())
                    .writeLong(mPositionAccuracyMetersReportsStatistics.getCount())
                    .writeLong(mPositionAccuracyMetersReportsStatistics.getLongSum())
                    .writeLong(mTopFourAverageCn0DbmHzReportsStatistics.getCount())
                    .writeLong(mTopFourAverageCn0DbmHzReportsStatistics.getLongSum())
                    .writeLong(mL5TopFourAverageCn0DbmHzReportsStatistics.getCount())
                    .writeLong(mL5TopFourAverageCn0DbmHzReportsStatistics.getLongSum())
                    .writeLong(mSvStatusReports)
                    .writeLong(mSvStatusReportsUsedInFix)
                    .writeLong(mL5SvStatusReports)
                    .writeLong(mL5SvStatusReportsUsedInFix)
                    .build();
            data.add(e);
            return StatsManager.PULL_SUCCESS;
        }
    }
}
