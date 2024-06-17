/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.os;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class WakeLockStatsTest {

    @Test
    public void isDataValidOfWakeLockData_invalid_returnFalse() {
        WakeLockStats.WakeLockData wakeLockData =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 0, /* totalTimeHeldMs= */ 10, /* timeHeldMs= */ 0);
        assertThat(wakeLockData.isDataValid()).isFalse();

        wakeLockData =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 1, /* totalTimeHeldMs= */ 0, /* timeHeldMs= */ 0);
        assertThat(wakeLockData.isDataValid()).isFalse();

        wakeLockData =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 1, /* totalTimeHeldMs= */ 10, /* timeHeldMs= */ -10);
        assertThat(wakeLockData.isDataValid()).isFalse();

        wakeLockData =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 1, /* totalTimeHeldMs= */ 10, /* timeHeldMs= */ 20);
        assertThat(wakeLockData.isDataValid()).isFalse();
    }

    @Test
    public void isDataValidOfWakeLockData_empty_returnTrue() {
        final WakeLockStats.WakeLockData wakeLockData = WakeLockStats.WakeLockData.EMPTY;
        assertThat(wakeLockData.isDataValid()).isTrue();
    }

    @Test
    public void isDataValidOfWakeLockData_valid_returnTrue() {
        WakeLockStats.WakeLockData wakeLockData =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 1, /* totalTimeHeldMs= */ 10, /* timeHeldMs= */ 5);
        assertThat(wakeLockData.isDataValid()).isTrue();
    }

    @Test
    public void isDataValidOfWakeLock_zeroTotalHeldMs_returnFalse() {
        final WakeLockStats.WakeLockData wakeLockData =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 0, /* totalTimeHeldMs= */ 0, /* timeHeldMs= */ 0);

        assertThat(WakeLockStats.WakeLock.isDataValid(wakeLockData, wakeLockData)).isFalse();
    }

    @Test
    public void isDataValidOfWakeLock_invalidData_returnFalse() {
        final WakeLockStats.WakeLockData totalWakeLockData =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 6, /* totalTimeHeldMs= */ 60, /* timeHeldMs= */ 20);
        final WakeLockStats.WakeLockData backgroundWakeLockData =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 0, /* totalTimeHeldMs= */ 10, /* timeHeldMs= */ 0);

        assertThat(WakeLockStats.WakeLock.isDataValid(totalWakeLockData, backgroundWakeLockData))
                .isFalse();
    }

    @Test
    public void isDataValidOfWakeLock_totalSmallerThanBackground_returnFalse() {
        final WakeLockStats.WakeLockData totalWakeLockData =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 10, /* totalTimeHeldMs= */ 60, /* timeHeldMs= */ 50);
        final WakeLockStats.WakeLockData backgroundWakeLockData =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 6, /* totalTimeHeldMs= */ 100, /* timeHeldMs= */ 30);

        assertThat(WakeLockStats.WakeLock.isDataValid(totalWakeLockData, backgroundWakeLockData))
                .isFalse();
    }

    @Test
    public void isDataValidOfWakeLock_returnTrue() {
        final WakeLockStats.WakeLockData totalWakeLockData =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 10, /* totalTimeHeldMs= */ 100, /* timeHeldMs= */ 50);
        final WakeLockStats.WakeLockData backgroundWakeLockData =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 6, /* totalTimeHeldMs= */ 60, /* timeHeldMs= */ 20);

        assertThat(WakeLockStats.WakeLock.isDataValid(totalWakeLockData, backgroundWakeLockData))
                .isTrue();
    }

    @Test
    public void parcelablity() {
        final WakeLockStats.WakeLockData totalWakeLockData1 =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 10, /* totalTimeHeldMs= */ 60, /* timeHeldMs= */ 50);
        final WakeLockStats.WakeLockData backgroundWakeLockData1 =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 6, /* totalTimeHeldMs= */ 100, /* timeHeldMs= */ 30);
        final WakeLockStats.WakeLock wakeLock1 =
                new WakeLockStats.WakeLock(
                        1, "foo", /* isAggregated= */ false, totalWakeLockData1,
                        backgroundWakeLockData1);
        final WakeLockStats.WakeLockData totalWakeLockData2 =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 20, /* totalTimeHeldMs= */ 80, /* timeHeldMs= */ 30);
        final WakeLockStats.WakeLockData backgroundWakeLockData2 =
                new WakeLockStats.WakeLockData(
                        /* timesAcquired= */ 1, /* totalTimeHeldMs= */ 100, /* timeHeldMs= */ 30);
        final WakeLockStats.WakeLock wakeLock2 =
                new WakeLockStats.WakeLock(
                        2, "bar", /* isAggregated= */ true, totalWakeLockData2,
                        backgroundWakeLockData2);
        WakeLockStats wakeLockStats = new WakeLockStats(
                List.of(wakeLock1), List.of(wakeLock2));

        Parcel parcel = Parcel.obtain();
        wakeLockStats.writeToParcel(parcel, 0);
        byte[] bytes = parcel.marshall();
        parcel.recycle();

        parcel = Parcel.obtain();
        parcel.unmarshall(bytes, 0, bytes.length);
        parcel.setDataPosition(0);

        WakeLockStats actual = WakeLockStats.CREATOR.createFromParcel(parcel);
        assertThat(actual.getWakeLocks()).hasSize(1);
        WakeLockStats.WakeLock actualWakelock = actual.getWakeLocks().get(0);
        assertThat(actualWakelock.uid).isEqualTo(1);
        assertThat(actualWakelock.name).isEqualTo("foo");
        assertThat(actualWakelock.isAggregated).isFalse();
        assertThat(actualWakelock.totalWakeLockData.timesAcquired).isEqualTo(10);
        assertThat(actualWakelock.totalWakeLockData.totalTimeHeldMs).isEqualTo(60);
        assertThat(actualWakelock.totalWakeLockData.timeHeldMs).isEqualTo(50);
        assertThat(actualWakelock.backgroundWakeLockData.timesAcquired).isEqualTo(6);
        assertThat(actualWakelock.backgroundWakeLockData.totalTimeHeldMs).isEqualTo(100);
        assertThat(actualWakelock.backgroundWakeLockData.timeHeldMs).isEqualTo(30);

        assertThat(actual.getAggregatedWakeLocks()).hasSize(1);
        WakeLockStats.WakeLock actualAggregatedWakelock = actual.getAggregatedWakeLocks().get(0);
        assertThat(actualAggregatedWakelock.uid).isEqualTo(2);
        assertThat(actualAggregatedWakelock.name).isEqualTo("bar");
        assertThat(actualAggregatedWakelock.isAggregated).isTrue();
        assertThat(actualAggregatedWakelock.totalWakeLockData.timesAcquired).isEqualTo(20);
        assertThat(actualAggregatedWakelock.totalWakeLockData.totalTimeHeldMs).isEqualTo(80);
        assertThat(actualAggregatedWakelock.totalWakeLockData.timeHeldMs).isEqualTo(30);
        assertThat(actualAggregatedWakelock.backgroundWakeLockData.timesAcquired).isEqualTo(1);
        assertThat(actualAggregatedWakelock.backgroundWakeLockData.totalTimeHeldMs).isEqualTo(100);
        assertThat(actualAggregatedWakelock.backgroundWakeLockData.timeHeldMs).isEqualTo(30);
    }
}