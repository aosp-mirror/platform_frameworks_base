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

package android.hardware.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.os.Parcel;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AmbientBrightnessDayStatsTest {

    @Test
    public void testAmbientBrightnessDayStatsAdd() {
        AmbientBrightnessDayStats dayStats = new AmbientBrightnessDayStats(LocalDate.now(),
                new float[]{0, 1, 10, 100});
        dayStats.log(0, 1);
        dayStats.log(0.5f, 1.5f);
        dayStats.log(50, 12.5f);
        dayStats.log(2000, 1.24f);
        dayStats.log(-10, 0.5f);
        assertEquals(2.5f, dayStats.getStats()[0], 0);
        assertEquals(0, dayStats.getStats()[1], 0);
        assertEquals(12.5f, dayStats.getStats()[2], 0);
        assertEquals(1.24f, dayStats.getStats()[3], 0);
    }

    @Test
    public void testAmbientBrightnessDayStatsEquals() {
        LocalDate today = LocalDate.now();
        AmbientBrightnessDayStats dayStats1 = new AmbientBrightnessDayStats(today,
                new float[]{0, 1, 10, 100});
        AmbientBrightnessDayStats dayStats2 = new AmbientBrightnessDayStats(today,
                new float[]{0, 1, 10, 100}, new float[4]);
        AmbientBrightnessDayStats dayStats3 = new AmbientBrightnessDayStats(today,
                new float[]{0, 1, 10, 100}, new float[]{1, 3, 5, 7});
        AmbientBrightnessDayStats dayStats4 = new AmbientBrightnessDayStats(today,
                new float[]{0, 1, 10, 100}, new float[]{1, 3, 5, 0});
        assertEquals(dayStats1, dayStats2);
        assertEquals(dayStats1.hashCode(), dayStats2.hashCode());
        assertNotEquals(dayStats1, dayStats3);
        assertNotEquals(dayStats1.hashCode(), dayStats3.hashCode());
        dayStats4.log(100, 7);
        assertEquals(dayStats3, dayStats4);
        assertEquals(dayStats3.hashCode(), dayStats4.hashCode());
    }

    @Test
    public void testAmbientBrightnessDayStatsIncorrectInit() {
        try {
            new AmbientBrightnessDayStats(LocalDate.now(), new float[]{1, 10, 100},
                    new float[]{1, 5, 6, 7});
        } catch (IllegalArgumentException e) {
            // Expected
        }
        try {
            new AmbientBrightnessDayStats(LocalDate.now(), new float[]{});
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }

    @Test
    public void testParcelUnparcelAmbientBrightnessDayStats() {
        LocalDate today = LocalDate.now();
        AmbientBrightnessDayStats stats = new AmbientBrightnessDayStats(today,
                new float[]{0, 1, 10, 100}, new float[]{1.3f, 2.6f, 5.8f, 10});
        // Parcel the data
        Parcel parcel = Parcel.obtain();
        stats.writeToParcel(parcel, 0);
        byte[] parceled = parcel.marshall();
        parcel.recycle();
        // Unparcel and check that it has not changed
        parcel = Parcel.obtain();
        parcel.unmarshall(parceled, 0, parceled.length);
        parcel.setDataPosition(0);
        AmbientBrightnessDayStats statsAgain = AmbientBrightnessDayStats.CREATOR.createFromParcel(
                parcel);
        assertEquals(stats, statsAgain);
    }
}
