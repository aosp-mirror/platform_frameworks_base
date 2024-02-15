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
    public void parcelablity() {
        WakeLockStats wakeLockStats = new WakeLockStats(
                List.of(new WakeLockStats.WakeLock(1, "foo", 200, 3000, 40000),
                        new WakeLockStats.WakeLock(2, "bar", 500, 6000, 70000)));

        Parcel parcel = Parcel.obtain();
        wakeLockStats.writeToParcel(parcel, 0);
        byte[] bytes = parcel.marshall();
        parcel.recycle();

        parcel = Parcel.obtain();
        parcel.unmarshall(bytes, 0, bytes.length);
        parcel.setDataPosition(0);

        WakeLockStats actual = WakeLockStats.CREATOR.createFromParcel(parcel);
        assertThat(actual.getWakeLocks()).hasSize(2);
        WakeLockStats.WakeLock wl1 = actual.getWakeLocks().get(0);
        assertThat(wl1.uid).isEqualTo(1);
        assertThat(wl1.name).isEqualTo("foo");
        assertThat(wl1.timesAcquired).isEqualTo(200);
        assertThat(wl1.totalTimeHeldMs).isEqualTo(3000);
        assertThat(wl1.timeHeldMs).isEqualTo(40000);

        WakeLockStats.WakeLock wl2 = actual.getWakeLocks().get(1);
        assertThat(wl2.uid).isEqualTo(2);
    }
}
