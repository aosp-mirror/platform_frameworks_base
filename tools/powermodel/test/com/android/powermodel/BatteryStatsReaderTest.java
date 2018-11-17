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

import com.android.powermodel.component.ModemAppActivity;
import com.android.powermodel.component.ModemGlobalActivity;
import com.android.powermodel.component.ModemRemainderActivity;

/**
 * Tests {@link BatteryStatsReader}.
 */
public class BatteryStatsReaderTest {
    private static InputStream loadCsvStream() {
        return BatteryStatsReaderTest.class.getResourceAsStream("/bs.csv");
    }

    @Test public void testModemGlobal() throws Exception {
        final ActivityReport report = BatteryStatsReader.parse(loadCsvStream());

        final AppActivity global = report.findApp(SpecialApp.GLOBAL);
        Assert.assertNotNull(global);

        final ModemGlobalActivity modem
                = (ModemGlobalActivity)global.getComponentActivity(Component.MODEM);
        Assert.assertNotNull(modem);
        Assert.assertEquals(97840, modem.rxPacketCount);
        Assert.assertEquals(72941, modem.txPacketCount);
        Assert.assertEquals(5113727, modem.totalActiveTimeMs);
    }

    @Test public void testModemApp() throws Exception {
        final ActivityReport report = BatteryStatsReader.parse(loadCsvStream());

        final List<AppActivity> gmailList = report.findApp("com.google.android.gm");
        Assert.assertEquals(1, gmailList.size());
        final AppActivity gmail = gmailList.get(0);

        final ModemAppActivity modem
                = (ModemAppActivity)gmail.getComponentActivity(Component.MODEM);
        Assert.assertNotNull(modem);
        Assert.assertEquals(9925, modem.rxPacketCount);
        Assert.assertEquals(5577, modem.txPacketCount);
    }

    @Test public void testModemRemainder() throws Exception {
        final ActivityReport report = BatteryStatsReader.parse(loadCsvStream());

        final AppActivity remainder = report.findApp(SpecialApp.REMAINDER);
        Assert.assertNotNull(remainder);

        final ModemRemainderActivity modem
                = (ModemRemainderActivity)remainder.getComponentActivity(Component.MODEM);
        Assert.assertNotNull(modem);
        Assert.assertArrayEquals(new long[] { 3066958, 0, 34678, 1643364, 7045084 },
                modem.strengthTimeMs);
        Assert.assertEquals(2443805, modem.scanningTimeMs);
        Assert.assertEquals(4923676, modem.activeTimeMs);
    }
}
