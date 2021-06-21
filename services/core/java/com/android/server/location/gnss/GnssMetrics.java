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

package com.android.server.location.gnss;

import android.app.StatsManager;
import android.content.Context;
import android.location.GnssSignalQuality;
import android.location.GnssStatus;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.connectivity.GpsBatteryStats;
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
import com.android.server.location.gnss.hal.GnssNative;

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

    private static final String TAG = "GnssMetrics";

    /** Default time between location fixes (in millisecs) */
    private static final int DEFAULT_TIME_BETWEEN_FIXES_MILLISECS = 1000;
    private static final int CONVERT_MILLI_TO_MICRO = 1000;
    private static final int VENDOR_SPECIFIC_POWER_MODES_SIZE = 10;

    /** Frequency range of GPS L5, Galileo E5a, QZSS J5 frequency band */
    private static final double L5_CARRIER_FREQ_RANGE_LOW_HZ = 1164 * 1e6;
    private static final double L5_CARRIER_FREQ_RANGE_HIGH_HZ = 1189 * 1e6;


    private long mLogStartInElapsedRealtimeMs;

    GnssPowerMetrics mGnssPowerMetrics;

    // A boolean array indicating whether the constellation types have been used in fix.
    private boolean[] mConstellationTypes;
    private final Statistics mLocationFailureStatistics;
    private final Statistics mTimeToFirstFixSecStatistics;
    private final Statistics mPositionAccuracyMeterStatistics;
    private final Statistics mTopFourAverageCn0Statistics;
    private final Statistics mTopFourAverageCn0StatisticsL5;
    // Total number of sv status messages processed
    private int mNumSvStatus;
    // Total number of L5 sv status messages processed
    private int mNumL5SvStatus;
    // Total number of sv status messages processed, where sv is used in fix
    private int mNumSvStatusUsedInFix;
    // Total number of L5 sv status messages processed, where sv is used in fix
    private int mNumL5SvStatusUsedInFix;

    Statistics mLocationFailureReportsStatistics;
    Statistics mTimeToFirstFixMilliSReportsStatistics;
    Statistics mPositionAccuracyMetersReportsStatistics;
    Statistics mTopFourAverageCn0DbmHzReportsStatistics;
    Statistics mL5TopFourAverageCn0DbmHzReportsStatistics;
    long mSvStatusReports;
    long mL5SvStatusReports;
    long mSvStatusReportsUsedInFix;
    long mL5SvStatusReportsUsedInFix;

    private final StatsManager mStatsManager;
    private final GnssNative mGnssNative;

    public GnssMetrics(Context context, IBatteryStats stats, GnssNative gnssNative) {
        mGnssNative = gnssNative;
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
        mTimeToFirstFixSecStatistics.addItem(((double) timeToFirstFixMilliSeconds) / 1000);
        mTimeToFirstFixMilliSReportsStatistics.addItem(timeToFirstFixMilliSeconds);
    }

    /**
     * Logs position accuracy
     */
    public void logPositionAccuracyMeters(float positionAccuracyMeters) {
        mPositionAccuracyMeterStatistics.addItem(positionAccuracyMeters);
        mPositionAccuracyMetersReportsStatistics.addItem(positionAccuracyMeters);
    }

    /**
     * Logs CN0 when at least 4 SVs are available
     */
    public void logCn0(GnssStatus gnssStatus) {
        logCn0L5(gnssStatus);

        if (gnssStatus.getSatelliteCount() == 0) {
            mGnssPowerMetrics.reportSignalQuality(null);
            return;
        }

        float[] cn0DbHzs = new float[gnssStatus.getSatelliteCount()];
        for (int i = 0; i < gnssStatus.getSatelliteCount(); i++) {
            cn0DbHzs[i] = gnssStatus.getCn0DbHz(i);
        }

        Arrays.sort(cn0DbHzs);
        mGnssPowerMetrics.reportSignalQuality(cn0DbHzs);
        if (cn0DbHzs.length < 4) {
            return;
        }
        if (cn0DbHzs[cn0DbHzs.length - 4] > 0.0) {
            double top4AvgCn0 = 0.0;
            for (int i = cn0DbHzs.length - 4; i < cn0DbHzs.length; i++) {
                top4AvgCn0 += cn0DbHzs[i];
            }
            top4AvgCn0 /= 4;
            mTopFourAverageCn0Statistics.addItem(top4AvgCn0);
            // Convert to mHz for accuracy
            mTopFourAverageCn0DbmHzReportsStatistics.addItem(top4AvgCn0 * 1000);
        }
    }

    private static boolean isL5Sv(float carrierFreq) {
        return (carrierFreq >= L5_CARRIER_FREQ_RANGE_LOW_HZ
                && carrierFreq <= L5_CARRIER_FREQ_RANGE_HIGH_HZ);
    }

    /**
     * Logs sv status data
     */
    public void logSvStatus(GnssStatus status) {
        boolean isL5;
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
    private void logCn0L5(GnssStatus gnssStatus) {
        if (gnssStatus.getSatelliteCount() == 0) {
            return;
        }
        // Create array list of all L5 satellites in report.
        ArrayList<Float> l5Cn0DbHzs = new ArrayList<>(gnssStatus.getSatelliteCount());
        for (int i = 0; i < gnssStatus.getSatelliteCount(); i++) {
            if (isL5Sv(gnssStatus.getCarrierFrequencyHz(i))) {
                l5Cn0DbHzs.add(gnssStatus.getCn0DbHz(i));
            }
        }
        if (l5Cn0DbHzs.size() < 4) {
            return;
        }

        Collections.sort(l5Cn0DbHzs);
        if (l5Cn0DbHzs.get(l5Cn0DbHzs.size() - 4) > 0.0) {
            double top4AvgCn0 = 0.0;
            for (int i = l5Cn0DbHzs.size() - 4; i < l5Cn0DbHzs.size(); i++) {
                top4AvgCn0 += l5Cn0DbHzs.get(i);
            }
            top4AvgCn0 /= 4;
            mTopFourAverageCn0StatisticsL5.addItem(top4AvgCn0);
            // Convert to mHz for accuracy
            mL5TopFourAverageCn0DbmHzReportsStatistics.addItem(top4AvgCn0 * 1000);
        }
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
        s.append("  KPI logging start time: ");
        TimeUtils.formatDuration(mLogStartInElapsedRealtimeMs, s);
        s.append("\n");
        s.append("  KPI logging end time: ");
        TimeUtils.formatDuration(SystemClock.elapsedRealtime(), s);
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
            if (t != null && t.length == GnssSignalQuality.NUM_GNSS_SIGNAL_QUALITY_LEVELS) {
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
        mLogStartInElapsedRealtimeMs = SystemClock.elapsedRealtime();
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

        Statistics() {
        }

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

        GnssPowerMetrics(IBatteryStats stats) {
            mBatteryStats = stats;
            // Used to initialize the variable to a very small value (unachievable in practice)
            // so that
            // the first CNO report will trigger an update to BatteryStats
            mLastAverageCn0 = -100.0;
            mLastSignalLevel = GnssSignalQuality.GNSS_SIGNAL_QUALITY_UNKNOWN;
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
                System.arraycopy(t, 0, p.timeInSignalQualityLevelMs, 0, t.length);
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
            } catch (RemoteException e) {
                Log.w(TAG, e);
                return null;
            }
        }

        /**
         * Reports signal quality to BatteryStats. Signal quality is based on Top four average CN0.
         * If the number of SVs seen is less than 4, then signal quality is the average CN0.
         * Changes are reported only if the average CN0 changes by more than
         * REPORTING_THRESHOLD_DB_HZ.
         */
        public void reportSignalQuality(float[] sortedCn0DbHzs) {
            double avgCn0 = 0.0;
            if (sortedCn0DbHzs != null && sortedCn0DbHzs.length > 0) {
                for (int i = Math.max(0, sortedCn0DbHzs.length - 4); i < sortedCn0DbHzs.length;
                        i++) {
                    avgCn0 += sortedCn0DbHzs[i];
                }
                avgCn0 /= Math.min(sortedCn0DbHzs.length, 4);
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
            } catch (RemoteException e) {
                Log.w(TAG, e);
            }
        }

        /**
         * Obtains signal level based on CN0
         */
        private int getSignalLevel(double cn0) {
            if (cn0 > POOR_TOP_FOUR_AVG_CN0_THRESHOLD_DB_HZ) {
                return GnssSignalQuality.GNSS_SIGNAL_QUALITY_GOOD;
            }
            return GnssSignalQuality.GNSS_SIGNAL_QUALITY_POOR;
        }
    }

    private void registerGnssStats() {
        StatsPullAtomCallbackImpl pullAtomCallback = new StatsPullAtomCallbackImpl();
        mStatsManager.setPullAtomCallback(
                FrameworkStatsLog.GNSS_STATS,
                null, // use default PullAtomMetadata values
                ConcurrentUtils.DIRECT_EXECUTOR, pullAtomCallback);
        mStatsManager.setPullAtomCallback(
                FrameworkStatsLog.GNSS_POWER_STATS,
                null, // use default PullAtomMetadata values
                ConcurrentUtils.DIRECT_EXECUTOR, pullAtomCallback);
    }

    /**
     * Stats Pull Atom Callback
     * Calls the pull method to fill out gnss stats
     */
    private class StatsPullAtomCallbackImpl implements StatsManager.StatsPullAtomCallback {

        StatsPullAtomCallbackImpl() {
        }

        @Override
        public int onPullAtom(int atomTag, List<StatsEvent> data) {
            if (atomTag == FrameworkStatsLog.GNSS_STATS) {
                data.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                        mLocationFailureReportsStatistics.getCount(),
                        mLocationFailureReportsStatistics.getLongSum(),
                        mTimeToFirstFixMilliSReportsStatistics.getCount(),
                        mTimeToFirstFixMilliSReportsStatistics.getLongSum(),
                        mPositionAccuracyMetersReportsStatistics.getCount(),
                        mPositionAccuracyMetersReportsStatistics.getLongSum(),
                        mTopFourAverageCn0DbmHzReportsStatistics.getCount(),
                        mTopFourAverageCn0DbmHzReportsStatistics.getLongSum(),
                        mL5TopFourAverageCn0DbmHzReportsStatistics.getCount(),
                        mL5TopFourAverageCn0DbmHzReportsStatistics.getLongSum(), mSvStatusReports,
                        mSvStatusReportsUsedInFix, mL5SvStatusReports,
                        mL5SvStatusReportsUsedInFix));
            } else if (atomTag == FrameworkStatsLog.GNSS_POWER_STATS) {
                mGnssNative.requestPowerStats();
                GnssPowerStats gnssPowerStats = mGnssNative.getPowerStats();
                if (gnssPowerStats == null) {
                    return StatsManager.PULL_SKIP;
                }
                double[] otherModesEnergyMilliJoule = new double[VENDOR_SPECIFIC_POWER_MODES_SIZE];
                double[] tempGnssPowerStatsOtherModes =
                        gnssPowerStats.getOtherModesEnergyMilliJoule();
                if (tempGnssPowerStatsOtherModes.length < VENDOR_SPECIFIC_POWER_MODES_SIZE) {
                    System.arraycopy(tempGnssPowerStatsOtherModes, 0,
                            otherModesEnergyMilliJoule, 0,
                            tempGnssPowerStatsOtherModes.length);
                } else {
                    System.arraycopy(tempGnssPowerStatsOtherModes, 0,
                            otherModesEnergyMilliJoule, 0,
                            VENDOR_SPECIFIC_POWER_MODES_SIZE);
                }
                data.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                        (long) (gnssPowerStats.getElapsedRealtimeUncertaintyNanos()),
                        (long) (gnssPowerStats.getTotalEnergyMilliJoule() * CONVERT_MILLI_TO_MICRO),
                        (long) (gnssPowerStats.getSinglebandTrackingModeEnergyMilliJoule()
                                * CONVERT_MILLI_TO_MICRO),
                        (long) (gnssPowerStats.getMultibandTrackingModeEnergyMilliJoule()
                                * CONVERT_MILLI_TO_MICRO),
                        (long) (gnssPowerStats.getSinglebandAcquisitionModeEnergyMilliJoule()
                                * CONVERT_MILLI_TO_MICRO),
                        (long) (gnssPowerStats.getMultibandAcquisitionModeEnergyMilliJoule()
                                * CONVERT_MILLI_TO_MICRO),
                        (long) (otherModesEnergyMilliJoule[0] * CONVERT_MILLI_TO_MICRO),
                        (long) (otherModesEnergyMilliJoule[1] * CONVERT_MILLI_TO_MICRO),
                        (long) (otherModesEnergyMilliJoule[2] * CONVERT_MILLI_TO_MICRO),
                        (long) (otherModesEnergyMilliJoule[3] * CONVERT_MILLI_TO_MICRO),
                        (long) (otherModesEnergyMilliJoule[4] * CONVERT_MILLI_TO_MICRO),
                        (long) (otherModesEnergyMilliJoule[5] * CONVERT_MILLI_TO_MICRO),
                        (long) (otherModesEnergyMilliJoule[6] * CONVERT_MILLI_TO_MICRO),
                        (long) (otherModesEnergyMilliJoule[7] * CONVERT_MILLI_TO_MICRO),
                        (long) (otherModesEnergyMilliJoule[8] * CONVERT_MILLI_TO_MICRO),
                        (long) (otherModesEnergyMilliJoule[9] * CONVERT_MILLI_TO_MICRO)));
            } else {
                throw new UnsupportedOperationException("Unknown tagId = " + atomTag);
            }
            return StatsManager.PULL_SUCCESS;
        }
    }
}