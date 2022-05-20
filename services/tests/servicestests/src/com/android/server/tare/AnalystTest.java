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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/** Test that the Analyst processes transactions correctly. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class AnalystTest {

    @Test
    public void testInitialState() {
        final Analyst analyst = new Analyst();
        assertEquals(0, analyst.getReports().size());
    }

    @Test
    public void testBatteryLevelChange() {
        final Analyst analyst = new Analyst();

        Analyst.Report expected = new Analyst.Report();
        expected.currentBatteryLevel = 55;
        analyst.noteBatteryLevelChange(55);
        assertEquals(1, analyst.getReports().size());
        assertReportsEqual(expected, analyst.getReports().get(0));

        // Discharging
        analyst.noteBatteryLevelChange(54);
        expected.currentBatteryLevel = 54;
        expected.cumulativeBatteryDischarge = 1;
        assertEquals(1, analyst.getReports().size());
        assertReportsEqual(expected, analyst.getReports().get(0));
        analyst.noteBatteryLevelChange(50);
        expected.currentBatteryLevel = 50;
        expected.cumulativeBatteryDischarge = 5;
        assertEquals(1, analyst.getReports().size());
        assertReportsEqual(expected, analyst.getReports().get(0));

        // Charging
        analyst.noteBatteryLevelChange(51);
        expected.currentBatteryLevel = 51;
        assertEquals(1, analyst.getReports().size());
        assertReportsEqual(expected, analyst.getReports().get(0));
        analyst.noteBatteryLevelChange(55);
        expected.currentBatteryLevel = 55;
        assertEquals(1, analyst.getReports().size());
        assertReportsEqual(expected, analyst.getReports().get(0));

        // Reset
        analyst.noteBatteryLevelChange(100);
        assertEquals(2, analyst.getReports().size());
        assertReportsEqual(expected, analyst.getReports().get(0));
        expected.currentBatteryLevel = 100;
        expected.cumulativeBatteryDischarge = 0;
        assertReportsEqual(expected, analyst.getReports().get(1));
    }

    @Test
    public void testTransaction() {
        runTestTransactions(new Analyst(), new Analyst.Report(), 1);
    }

    @Test
    public void testTransaction_PeriodChange() {
        final Analyst analyst = new Analyst();

        Analyst.Report expected = new Analyst.Report();
        expected.currentBatteryLevel = 55;
        analyst.noteBatteryLevelChange(55);

        runTestTransactions(analyst, expected, 1);

        expected.currentBatteryLevel = 49;
        expected.cumulativeBatteryDischarge = 6;
        analyst.noteBatteryLevelChange(49);

        runTestTransactions(analyst, expected, 1);

        expected = new Analyst.Report();
        expected.currentBatteryLevel = 100;
        analyst.noteBatteryLevelChange(100);
        expected.cumulativeBatteryDischarge = 0;

        runTestTransactions(analyst, expected, 2);
    }

    private void runTestTransactions(Analyst analyst, Analyst.Report lastExpectedReport,
            int numExpectedReports) {
        Analyst.Report expected = lastExpectedReport;

        // Profit
        analyst.noteTransaction(
                new Ledger.Transaction(0, 1000, EconomicPolicy.TYPE_ACTION, null, -51, 1));
        expected.cumulativeProfit += 50;
        expected.numProfitableActions += 1;
        assertEquals(numExpectedReports, analyst.getReports().size());
        assertReportsEqual(expected, analyst.getReports().get(numExpectedReports - 1));

        // Loss
        analyst.noteTransaction(
                new Ledger.Transaction(0, 1000, EconomicPolicy.TYPE_ACTION, null, -51, 100));
        expected.cumulativeLoss += 49;
        expected.numUnprofitableActions += 1;
        assertEquals(numExpectedReports, analyst.getReports().size());
        assertReportsEqual(expected, analyst.getReports().get(numExpectedReports - 1));

        // Reward
        analyst.noteTransaction(
                new Ledger.Transaction(0, 1000, EconomicPolicy.TYPE_REWARD, null, 51, 0));
        expected.cumulativeRewards += 51;
        expected.numRewards += 1;
        assertEquals(numExpectedReports, analyst.getReports().size());
        assertReportsEqual(expected, analyst.getReports().get(numExpectedReports - 1));

        // Regulations
        analyst.noteTransaction(
                new Ledger.Transaction(0, 1000, EconomicPolicy.TYPE_REGULATION, null, 25, 0));
        expected.cumulativePositiveRegulations += 25;
        expected.numPositiveRegulations += 1;
        assertEquals(numExpectedReports, analyst.getReports().size());
        assertReportsEqual(expected, analyst.getReports().get(numExpectedReports - 1));
        analyst.noteTransaction(
                new Ledger.Transaction(0, 1000, EconomicPolicy.TYPE_REGULATION, null, -25, 0));
        expected.cumulativeNegativeRegulations += 25;
        expected.numNegativeRegulations += 1;
        assertEquals(numExpectedReports, analyst.getReports().size());
        assertReportsEqual(expected, analyst.getReports().get(numExpectedReports - 1));

        // No-ops
        analyst.noteTransaction(
                new Ledger.Transaction(0, 1000, EconomicPolicy.TYPE_ACTION, null, -100, 100));
        analyst.noteTransaction(
                new Ledger.Transaction(0, 1000, EconomicPolicy.TYPE_REGULATION, null, 0, 0));
        analyst.noteTransaction(
                new Ledger.Transaction(0, 1000, EconomicPolicy.TYPE_REWARD, null, 0, 0));
        assertEquals(numExpectedReports, analyst.getReports().size());
    }

    @Test
    public void testLoadReports() {
        final Analyst analyst = new Analyst();

        List<Analyst.Report> expected = new ArrayList<>();
        analyst.loadReports(expected);
        assertReportListsEqual(expected, analyst.getReports());

        Analyst.Report report1 = new Analyst.Report();
        report1.cumulativeBatteryDischarge = 1;
        report1.currentBatteryLevel = 2;
        report1.cumulativeProfit = 3;
        report1.numProfitableActions = 4;
        report1.cumulativeLoss = 5;
        report1.numUnprofitableActions = 6;
        report1.cumulativeRewards = 7;
        report1.numRewards = 8;
        report1.cumulativePositiveRegulations = 9;
        report1.numPositiveRegulations = 10;
        report1.cumulativeNegativeRegulations = 11;
        report1.numNegativeRegulations = 12;
        expected.add(report1);
        analyst.loadReports(expected);
        assertReportListsEqual(expected, analyst.getReports());

        Analyst.Report report2 = new Analyst.Report();
        report2.cumulativeBatteryDischarge = 10;
        report2.currentBatteryLevel = 20;
        report2.cumulativeProfit = 30;
        report2.numProfitableActions = 40;
        report2.cumulativeLoss = 50;
        report2.numUnprofitableActions = 60;
        report2.cumulativeRewards = 70;
        report2.numRewards = 80;
        report2.cumulativePositiveRegulations = 90;
        report2.numPositiveRegulations = 100;
        report2.cumulativeNegativeRegulations = 110;
        report2.numNegativeRegulations = 120;
        expected.add(report2);
        analyst.loadReports(expected);
        assertReportListsEqual(expected, analyst.getReports());
    }

    private void assertReportsEqual(Analyst.Report expected, Analyst.Report actual) {
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertNotNull(actual);
        assertEquals(expected.cumulativeBatteryDischarge, actual.cumulativeBatteryDischarge);
        assertEquals(expected.currentBatteryLevel, actual.currentBatteryLevel);
        assertEquals(expected.cumulativeProfit, actual.cumulativeProfit);
        assertEquals(expected.numProfitableActions, actual.numProfitableActions);
        assertEquals(expected.cumulativeLoss, actual.cumulativeLoss);
        assertEquals(expected.numUnprofitableActions, actual.numUnprofitableActions);
        assertEquals(expected.cumulativeRewards, actual.cumulativeRewards);
        assertEquals(expected.numRewards, actual.numRewards);
        assertEquals(expected.cumulativePositiveRegulations, actual.cumulativePositiveRegulations);
        assertEquals(expected.numPositiveRegulations, actual.numPositiveRegulations);
        assertEquals(expected.cumulativeNegativeRegulations, actual.cumulativeNegativeRegulations);
        assertEquals(expected.numNegativeRegulations, actual.numNegativeRegulations);
    }

    private void assertReportListsEqual(List<Analyst.Report> expected,
            List<Analyst.Report> actual) {
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertNotNull(actual);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); ++i) {
            assertReportsEqual(expected.get(i), actual.get(i));
        }
    }
}
