/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.tare;

import static com.android.server.tare.EconomicPolicy.TYPE_ACTION;
import static com.android.server.tare.EconomicPolicy.TYPE_REGULATION;
import static com.android.server.tare.EconomicPolicy.TYPE_REWARD;
import static com.android.server.tare.EconomicPolicy.getEventType;
import static com.android.server.tare.TareUtils.cakeToString;

import android.annotation.NonNull;
import android.util.IndentingPrintWriter;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Responsible for maintaining statistics and analysis of TARE's performance.
 */
public class Analyst {
    private static final String TAG = "TARE-" + Analyst.class.getSimpleName();
    private static final boolean DEBUG = InternalResourceService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private static final int NUM_PERIODS_TO_RETAIN = 8;

    static final class Report {
        /** How much the battery was discharged over the tracked period. */
        public int cumulativeBatteryDischarge = 0;
        public int currentBatteryLevel = 0;
        /**
         * Profit from performing actions. This excludes special circumstances where we charge the
         * app
         * less than the action's CTP.
         */
        public long cumulativeProfit = 0;
        public int numProfitableActions = 0;
        /**
         * Losses from performing actions for special circumstances (eg. for a TOP app) where we
         * charge
         * the app less than the action's CTP.
         */
        public long cumulativeLoss = 0;
        public int numUnprofitableActions = 0;
        /**
         * The total number of rewards given to apps over this period.
         */
        public long cumulativeRewards = 0;
        public int numRewards = 0;
        /**
         * Regulations that increased an app's balance.
         */
        public long cumulativePositiveRegulations = 0;
        public int numPositiveRegulations = 0;
        /**
         * Regulations that decreased an app's balance.
         */
        public long cumulativeNegativeRegulations = 0;
        public int numNegativeRegulations = 0;

        private void clear() {
            cumulativeBatteryDischarge = 0;
            currentBatteryLevel = 0;
            cumulativeProfit = 0;
            numProfitableActions = 0;
            cumulativeLoss = 0;
            numUnprofitableActions = 0;
            cumulativeRewards = 0;
            numRewards = 0;
            cumulativePositiveRegulations = 0;
            numPositiveRegulations = 0;
            cumulativeNegativeRegulations = 0;
            numNegativeRegulations = 0;
        }
    }

    private int mPeriodIndex = 0;
    /** How much the battery was discharged over the tracked period. */
    private final Report[] mReports = new Report[NUM_PERIODS_TO_RETAIN];

    /** Returns the list of most recent reports, with the oldest report first. */
    @NonNull
    List<Report> getReports() {
        final List<Report> list = new ArrayList<>(NUM_PERIODS_TO_RETAIN);
        for (int i = 1; i <= NUM_PERIODS_TO_RETAIN; ++i) {
            final int idx = (mPeriodIndex + i) % NUM_PERIODS_TO_RETAIN;
            final Report report = mReports[idx];
            if (report != null) {
                list.add(report);
            }
        }
        return list;
    }

    /**
     * Tracks the given reports instead of whatever is currently saved. Reports should be ordered
     * oldest to most recent.
     */
    void loadReports(@NonNull List<Report> reports) {
        final int numReports = reports.size();
        mPeriodIndex = Math.max(0, numReports - 1);
        for (int i = 0; i < NUM_PERIODS_TO_RETAIN; ++i) {
            if (i < numReports) {
                mReports[i] = reports.get(i);
            } else {
                mReports[i] = null;
            }
        }
    }

    void noteBatteryLevelChange(int newBatteryLevel) {
        if (newBatteryLevel == 100 && mReports[mPeriodIndex] != null
                && mReports[mPeriodIndex].currentBatteryLevel < newBatteryLevel) {
            mPeriodIndex = (mPeriodIndex + 1) % NUM_PERIODS_TO_RETAIN;
            if (mReports[mPeriodIndex] != null) {
                final Report report = mReports[mPeriodIndex];
                report.clear();
                report.currentBatteryLevel = newBatteryLevel;
                return;
            }
        }

        if (mReports[mPeriodIndex] == null) {
            Report report = new Report();
            mReports[mPeriodIndex] = report;
            report.currentBatteryLevel = newBatteryLevel;
            return;
        }

        final Report report = mReports[mPeriodIndex];
        if (newBatteryLevel < report.currentBatteryLevel) {
            report.cumulativeBatteryDischarge += (report.currentBatteryLevel - newBatteryLevel);
        }
        report.currentBatteryLevel = newBatteryLevel;
    }

    void noteTransaction(@NonNull Ledger.Transaction transaction) {
        if (mReports[mPeriodIndex] == null) {
            mReports[mPeriodIndex] = new Report();
        }
        final Report report = mReports[mPeriodIndex];
        switch (getEventType(transaction.eventId)) {
            case TYPE_ACTION:
                // For now, assume all instances where price < CTP is a special instance.
                // TODO: add an explicit signal for special circumstances
                if (-transaction.delta > transaction.ctp) {
                    report.cumulativeProfit += (-transaction.delta - transaction.ctp);
                    report.numProfitableActions++;
                } else if (-transaction.delta < transaction.ctp) {
                    report.cumulativeLoss += (transaction.ctp + transaction.delta);
                    report.numUnprofitableActions++;
                }
                break;
            case TYPE_REGULATION:
                if (transaction.delta > 0) {
                    report.cumulativePositiveRegulations += transaction.delta;
                    report.numPositiveRegulations++;
                } else if (transaction.delta < 0) {
                    report.cumulativeNegativeRegulations -= transaction.delta;
                    report.numNegativeRegulations++;
                }
                break;
            case TYPE_REWARD:
                if (transaction.delta != 0) {
                    report.cumulativeRewards += transaction.delta;
                    report.numRewards++;
                }
                break;
        }
    }

    void tearDown() {
        for (int i = 0; i < mReports.length; ++i) {
            mReports[i] = null;
        }
        mPeriodIndex = 0;
    }

    @NonNull
    private String padStringWithSpaces(@NonNull String text, int targetLength) {
        // Make sure to have at least one space on either side.
        final int padding = Math.max(2, targetLength - text.length()) >>> 1;
        return " ".repeat(padding) + text + " ".repeat(padding);
    }

    void dump(IndentingPrintWriter pw) {
        pw.println("Reports:");
        pw.increaseIndent();
        pw.print("      Total Discharge");
        final int statColsLength = 47;
        pw.print(padStringWithSpaces("Profit (avg/action : avg/discharge)", statColsLength));
        pw.print(padStringWithSpaces("Loss (avg/action : avg/discharge)", statColsLength));
        pw.print(padStringWithSpaces("Rewards (avg/reward : avg/discharge)", statColsLength));
        pw.print(padStringWithSpaces("+Regs (avg/reg : avg/discharge)", statColsLength));
        pw.print(padStringWithSpaces("-Regs (avg/reg : avg/discharge)", statColsLength));
        pw.println();
        for (int r = 0; r < NUM_PERIODS_TO_RETAIN; ++r) {
            final int idx = (mPeriodIndex - r + NUM_PERIODS_TO_RETAIN) % NUM_PERIODS_TO_RETAIN;
            final Report report = mReports[idx];
            if (report == null) {
                continue;
            }
            pw.print("t-");
            pw.print(r);
            pw.print(":  ");
            pw.print(padStringWithSpaces(Integer.toString(report.cumulativeBatteryDischarge), 15));
            if (report.numProfitableActions > 0) {
                final String perDischarge = report.cumulativeBatteryDischarge > 0
                        ? cakeToString(report.cumulativeProfit / report.cumulativeBatteryDischarge)
                        : "N/A";
                pw.print(padStringWithSpaces(String.format("%s (%s : %s)",
                                cakeToString(report.cumulativeProfit),
                                cakeToString(report.cumulativeProfit / report.numProfitableActions),
                                perDischarge),
                        statColsLength));
            } else {
                pw.print(padStringWithSpaces("N/A", statColsLength));
            }
            if (report.numUnprofitableActions > 0) {
                final String perDischarge = report.cumulativeBatteryDischarge > 0
                        ? cakeToString(report.cumulativeLoss / report.cumulativeBatteryDischarge)
                        : "N/A";
                pw.print(padStringWithSpaces(String.format("%s (%s : %s)",
                                cakeToString(report.cumulativeLoss),
                                cakeToString(report.cumulativeLoss / report.numUnprofitableActions),
                                perDischarge),
                        statColsLength));
            } else {
                pw.print(padStringWithSpaces("N/A", statColsLength));
            }
            if (report.numRewards > 0) {
                final String perDischarge = report.cumulativeBatteryDischarge > 0
                        ? cakeToString(report.cumulativeRewards / report.cumulativeBatteryDischarge)
                        : "N/A";
                pw.print(padStringWithSpaces(String.format("%s (%s : %s)",
                                cakeToString(report.cumulativeRewards),
                                cakeToString(report.cumulativeRewards / report.numRewards),
                                perDischarge),
                        statColsLength));
            } else {
                pw.print(padStringWithSpaces("N/A", statColsLength));
            }
            if (report.numPositiveRegulations > 0) {
                final String perDischarge = report.cumulativeBatteryDischarge > 0
                        ? cakeToString(
                        report.cumulativePositiveRegulations / report.cumulativeBatteryDischarge)
                        : "N/A";
                pw.print(padStringWithSpaces(String.format("%s (%s : %s)",
                                cakeToString(report.cumulativePositiveRegulations),
                                cakeToString(report.cumulativePositiveRegulations
                                        / report.numPositiveRegulations),
                                perDischarge),
                        statColsLength));
            } else {
                pw.print(padStringWithSpaces("N/A", statColsLength));
            }
            if (report.numNegativeRegulations > 0) {
                final String perDischarge = report.cumulativeBatteryDischarge > 0
                        ? cakeToString(
                        report.cumulativeNegativeRegulations / report.cumulativeBatteryDischarge)
                        : "N/A";
                pw.print(padStringWithSpaces(String.format("%s (%s : %s)",
                                cakeToString(report.cumulativeNegativeRegulations),
                                cakeToString(report.cumulativeNegativeRegulations
                                        / report.numNegativeRegulations),
                                perDischarge),
                        statColsLength));
            } else {
                pw.print(padStringWithSpaces("N/A", statColsLength));
            }
            pw.println();
        }
        pw.decreaseIndent();
    }
}
