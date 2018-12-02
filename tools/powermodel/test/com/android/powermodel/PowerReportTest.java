/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.powermodel;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import org.junit.Assert;

import com.android.powermodel.component.ModemAppPower;
import com.android.powermodel.component.ModemRemainderPower;

/**
 * Tests {@link PowerReport}.
 */
public class PowerReportTest {
    private static final double EPSILON = 0.001;
    private static final double MS_PER_HR = 3600000.0;

    private static final double AVERAGE_MODEM_POWER = ((11+16+19+22+73+132) / 6.0);
    private static final double GMAIL_MODEM_MAH = ((9925+5577) / (double)(97840+72941))
            * 5113727 * AVERAGE_MODEM_POWER * (1.0 / 3600 / 1000);
    private static final double GMAIL_MAH
            = GMAIL_MODEM_MAH;

    private static final double REMAINDER_MODEM_MAH
            =  (1.0 / 3600 / 1000)
            * ((3066958 * 16) + (0 * 19) + (34678 * 22) + (1643364 * 73) + (7045084 * 132)
                + (2443805 * 12)
                + (4923676 * AVERAGE_MODEM_POWER));
    private static final double REMAINDER_MAH
            = REMAINDER_MODEM_MAH;

    private static final double TOTAL_MAH
            = GMAIL_MAH
            + REMAINDER_MAH;

    private static InputStream loadPowerProfileStream() {
        return PowerProfileTest.class.getResourceAsStream("/power_profile.xml");
    }

    private static InputStream loadCsvStream() {
        return BatteryStatsReaderTest.class.getResourceAsStream("/bs.csv");
    }

    private static PowerReport loadPowerReport() throws Exception {
        final PowerProfile profile = PowerProfile.parse(loadPowerProfileStream());
        final ActivityReport activity = BatteryStatsReader.parse(loadCsvStream());
        return PowerReport.createReport(profile, activity);
    }

    @Test public void testModemApp() throws Exception {
        final PowerReport report = loadPowerReport();

        final List<AppPower> gmailList = report.findApp("com.google.android.gm");
        Assert.assertEquals(1, gmailList.size());
        final AppPower gmail = gmailList.get(0);

        final ModemAppPower modem = (ModemAppPower)gmail.getComponentPower(Component.MODEM);
        Assert.assertNotNull(modem);
        Assert.assertEquals(GMAIL_MODEM_MAH, modem.powerMah, EPSILON);
    }

    @Test public void testModemRemainder() throws Exception {
        final PowerReport report = loadPowerReport();

        final AppPower remainder = report.findApp(SpecialApp.REMAINDER);
        Assert.assertNotNull(remainder);

        final ModemRemainderPower modem
                = (ModemRemainderPower)remainder.getComponentPower(Component.MODEM);
        Assert.assertNotNull(modem);

        Assert.assertArrayEquals(new double[] {
                    3066958 * 16.0 / MS_PER_HR,
                    0 * 19.0 / MS_PER_HR,
                    34678 * 22.0 / MS_PER_HR,
                    1643364 * 73.0 / MS_PER_HR,
                    7045084 * 132.0 / MS_PER_HR },
                modem.strengthMah, EPSILON);
        Assert.assertEquals(2443805 * 12 / MS_PER_HR, modem.scanningMah, EPSILON);
        Assert.assertEquals(4923676 * AVERAGE_MODEM_POWER / MS_PER_HR, modem.activeMah, EPSILON);

        Assert.assertEquals(REMAINDER_MODEM_MAH, modem.powerMah, EPSILON);
    }

    @Test public void testAppTotal() throws Exception {
        final PowerReport report = loadPowerReport();

        final List<AppPower> gmailList = report.findApp("com.google.android.gm");
        Assert.assertEquals(1, gmailList.size());
        final AppPower gmail = gmailList.get(0);

        Assert.assertEquals(GMAIL_MAH, gmail.getAppPowerMah(), EPSILON);
    }

    @Test public void testRemainderTotal() throws Exception {
        final PowerReport report = loadPowerReport();

        final AppPower remainder = report.findApp(SpecialApp.REMAINDER);
        Assert.assertNotNull(remainder);

        Assert.assertEquals(REMAINDER_MAH, remainder.getAppPowerMah(), EPSILON);
    }

    @Test public void testTotal() throws Exception {
        final PowerReport report = loadPowerReport();

        Assert.assertEquals(TOTAL_MAH, report.getTotalPowerMah(), EPSILON);
    }
}

