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

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class BluetoothBatteryStatsTest {

    @Test
    public void parcelability() {
        BluetoothBatteryStats stats = new BluetoothBatteryStats(List.of(
                new BluetoothBatteryStats.UidStats(42, 100, 200, 300, 400, 500),
                new BluetoothBatteryStats.UidStats(99, 600, 700, 800, 900, 999)
        ));

        Parcel parcel = Parcel.obtain();
        stats.writeToParcel(parcel, 0);
        byte[] bytes = parcel.marshall();
        parcel.recycle();

        parcel = Parcel.obtain();
        parcel.unmarshall(bytes, 0, bytes.length);
        parcel.setDataPosition(0);

        BluetoothBatteryStats actual = new BluetoothBatteryStats(parcel);

        assertThat(actual.getUidStats()).hasSize(2);

        BluetoothBatteryStats.UidStats uid1 = actual.getUidStats().stream()
                .filter(s->s.uid == 42).findFirst().get();

        assertThat(uid1.scanTimeMs).isEqualTo(100);
        assertThat(uid1.unoptimizedScanTimeMs).isEqualTo(200);
        assertThat(uid1.scanResultCount).isEqualTo(300);
        assertThat(uid1.rxTimeMs).isEqualTo(400);
        assertThat(uid1.txTimeMs).isEqualTo(500);

        BluetoothBatteryStats.UidStats uid2 = actual.getUidStats().stream()
                .filter(s->s.uid == 99).findFirst().get();
        assertThat(uid2.scanTimeMs).isEqualTo(600);
    }
}
