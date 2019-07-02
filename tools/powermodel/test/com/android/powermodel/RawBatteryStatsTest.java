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

/**
 * Tests {@link RawBatteryStats}.
 */
public class RawBatteryStatsTest {
    private static final int BS_VERSION = 32;

    private static InputStream makeCsv(String... lines) {
        return makeCsv(BS_VERSION, lines);
    }

    private static InputStream makeCsv(int version, String... lines) {
        final StringBuilder result = new StringBuilder("9,0,i,vers,");
        result.append(version);
        result.append(",177,PPR1.180326.002,PQ1A.181105.015\n");
        for (String line: lines) {
            result.append(line);
            result.append('\n');
        }
        return new ByteArrayInputStream(result.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test public void testVersion() throws Exception {
        final InputStream is = makeCsv();

        final RawBatteryStats bs = RawBatteryStats.parse(is);
        final List<RawBatteryStats.Record> records = bs.getRecords();
        final RawBatteryStats.Version line = (RawBatteryStats.Version)records.get(0);

        Assert.assertEquals(0, bs.getWarnings().size());
        Assert.assertEquals(true, line.complete);

        Assert.assertEquals(9, line.lineVersion);
        Assert.assertEquals(0, line.uid);
        Assert.assertEquals(RawBatteryStats.Category.INFO, line.category);
        Assert.assertEquals("vers", line.lineType);

        Assert.assertEquals(BS_VERSION, line.dumpsysVersion);
        Assert.assertEquals(177, line.parcelVersion);
        Assert.assertEquals("PPR1.180326.002", line.startPlatformVersion);
        Assert.assertEquals("PQ1A.181105.015", line.endPlatformVersion);
    }

    @Test public void testUid() throws Exception {
        final InputStream is = makeCsv("9,0,i,uid,1000,com.example.app");

        final RawBatteryStats bs = RawBatteryStats.parse(is);
        final List<RawBatteryStats.Record> records = bs.getRecords();
        final RawBatteryStats.Uid line = (RawBatteryStats.Uid)records.get(1);

        Assert.assertEquals(1000, line.uidKey);
        Assert.assertEquals("com.example.app", line.pkg);
    }

    @Test public void testVarargs() throws Exception {
        final InputStream is = makeCsv("9,0,i,gmcd,1,2,3,4,5,6,7");

        final RawBatteryStats bs = RawBatteryStats.parse(is);
        final List<RawBatteryStats.Record> records = bs.getRecords();
        final RawBatteryStats.GlobalModemController line
                = (RawBatteryStats.GlobalModemController)records.get(1);

        Assert.assertEquals(1, line.idleMs);
        Assert.assertEquals(2, line.rxTimeMs);
        Assert.assertEquals(3, line.powerMaMs);
        Assert.assertEquals(4, line.txTimeMs.length);
        Assert.assertEquals(4, line.txTimeMs[0]);
        Assert.assertEquals(5, line.txTimeMs[1]);
        Assert.assertEquals(6, line.txTimeMs[2]);
        Assert.assertEquals(7, line.txTimeMs[3]);
    }
}
