/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.os;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.os.BadParcelableException;
import android.os.Parcel;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LongMultiStateCounterTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    public void setStateAndUpdateValue() {
        LongMultiStateCounter counter = new LongMultiStateCounter(2);

        counter.updateValue(0, 1000);
        counter.setState(0, 1000);
        counter.setState(1, 2000);
        counter.setState(0, 4000);
        counter.updateValue(100, 9000);

        assertThat(counter.getCount(0)).isEqualTo(75);
        assertThat(counter.getCount(1)).isEqualTo(25);

        assertThat(counter.toString()).isEqualTo("[0: 75, 1: 25] updated: 9000 currentState: 0");
    }

    @Test
    public void setEnabled() {
        LongMultiStateCounter counter = new LongMultiStateCounter(2);
        counter.setState(0, 1000);
        counter.updateValue(0, 1000);
        counter.updateValue(100, 2000);

        assertThat(counter.getCount(0)).isEqualTo(100);

        counter.setEnabled(false, 3000);

        // Partially included, because the counter is disabled after the previous update
        counter.updateValue(200, 4000);

        // Count only 50%, because the counter was disabled for 50% of the time
        assertThat(counter.getCount(0)).isEqualTo(150);

        // Not counted because the counter is disabled
        counter.updateValue(250, 5000);

        counter.setEnabled(true, 6000);

        counter.updateValue(300, 7000);

        // Again, take 50% of the delta
        assertThat(counter.getCount(0)).isEqualTo(175);
    }

    @Test
    public void updateThenSetState_timestampOutOfOrder() {
        LongMultiStateCounter counter = new LongMultiStateCounter(2);
        counter.setState(0, 1000);
        counter.updateValue(0, 1000);
        counter.updateValue(100, 3000);
        counter.setState(0, 2000);  // Note out-of-order timestamp
        counter.updateValue(200, 4000);

        // If we did not explicitly handle this out-of-order update scenario, we would get
        // this result:
        //  1. Time in state-0 at this point is (4000-2000) = 2000
        //  2. Time since last update is (4000-3000) = 1000.
        //  3. Counter delta: 100
        //  4. Proportion of count-0
        //          = prevValue + delta * time-in-state / time-since-last-update
        //          = 100 + 100 * 2000 / 1000
        //          = 300
        // This would be problematic, because the part (300) would exceed the total (200)
        assertThat(counter.getCount(0)).isEqualTo(200);
    }

    @Test
    public void disableThenUpdate_timestampOutOfOrder() {
        LongMultiStateCounter counter = new LongMultiStateCounter(2);
        counter.setState(0, 1000);
        counter.updateValue(0, 1000);
        counter.setEnabled(false, 2000);
        counter.updateValue(123, 1001);  // Note out-of-order timestamp

        // If we did not explicitly handle this out-of-order update scenario, we would get
        // this result:
        //  1. Time in state-0 at this point is (2000-1000) = 1000
        //  2. Time since last update is (1001-1000) = 1.
        //  3. Counter delta: 100
        //  4. Proportion of count-0
        //          = delta * time-in-state / time-since-last-update
        //          = 123 * 1000 / 1
        //          = 123,000
        // This would be very very wrong, because the part (123,000) would exceed the total (123)
        assertThat(counter.getCount(0)).isEqualTo(123);
    }

    @Test
    public void updateThenEnable_timestampOutOfOrder() {
        LongMultiStateCounter counter = new LongMultiStateCounter(2);
        counter.setState(0, 1000);
        counter.updateValue(0, 1000);
        counter.setEnabled(false, 3000);
        counter.updateValue(100, 5000);
        // At this point the counter is 50, because it was disabled for half of the time
        counter.setEnabled(true, 4000); // Note out-of-order timestamp
        counter.updateValue(200, 6000);

        // If we did not explicitly handle this out-of-order update scenario, we would get
        // this result:
        //  1. Time in state-0 at this point is (6000-4000) = 2000
        //  2. Time since last update is (6000-5000) = 1000.
        //  3. Counter delta: 100
        //  4. Proportion of count-0
        //          = prevValue + delta * time-in-state / time-since-last-update
        //          = 50 + 100 * 2000 / 1000
        //          = 250
        // This would not be great, because the part (250) would exceed the total (200)
        assertThat(counter.getCount(0)).isEqualTo(150);
    }

    @Test
    public void reset() {
        LongMultiStateCounter counter = new LongMultiStateCounter(2);
        counter.setState(0, 1000);
        counter.updateValue(0, 1000);
        counter.updateValue(100, 2000);

        assertThat(counter.getCount(0)).isEqualTo(100);

        counter.reset();

        assertThat(counter.getCount(0)).isEqualTo(0);

        counter.updateValue(200, 3000);
        counter.updateValue(300, 4000);

        assertThat(counter.getCount(0)).isEqualTo(100);
    }

    @Test
    public void parceling() {
        LongMultiStateCounter counter = new LongMultiStateCounter(2);
        counter.updateValue(0, 1000);
        counter.setState(0, 1000);
        counter.updateValue(100, 2000);
        counter.setState(1, 2000);
        counter.updateValue(101, 3000);

        assertThat(counter.getCount(0)).isEqualTo(100);
        assertThat(counter.getCount(1)).isEqualTo(1);

        Parcel parcel = Parcel.obtain();
        counter.writeToParcel(parcel, 0);
        byte[] bytes = parcel.marshall();
        parcel.recycle();

        parcel = Parcel.obtain();
        parcel.unmarshall(bytes, 0, bytes.length);
        parcel.setDataPosition(0);

        LongMultiStateCounter newCounter = LongMultiStateCounter.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        assertThat(newCounter.getCount(0)).isEqualTo(100);
        assertThat(newCounter.getCount(1)).isEqualTo(1);

        // ==== Verify that the counter keeps accumulating after unparceling.

        // State, last update timestamp and current counts are undefined at this point.
        newCounter.setState(0, 100);
        newCounter.updateValue(300, 100);

        // A new base state and counters are established; we can continue accumulating deltas
        newCounter.updateValue(316, 200);

        assertThat(newCounter.getCount(0)).isEqualTo(116);
    }

    @Test
    public void createFromBadParcel() {
        // Check we don't crash the runtime if the Parcel data is bad (b/243434675).
        Parcel parcel = Parcel.obtain();
        parcel.writeInt(13);
        parcel.setDataPosition(0);
        assertThrows(RuntimeException.class,
                () -> LongMultiStateCounter.CREATOR.createFromParcel(parcel));
    }

    @Test
    public void createFromBadBundle() {
        Parcel data = Parcel.obtain();
        int bundleLenPos = data.dataPosition();
        data.writeInt(0);
        data.writeInt(0x4C444E42);      // BaseBundle.BUNDLE_MAGIC

        int bundleStart = data.dataPosition();

        data.writeInt(1);
        data.writeString("key");
        data.writeInt(4);
        int lazyValueLenPos = data.dataPosition();
        data.writeInt(0);
        int lazyValueStart = data.dataPosition();
        data.writeString("com.android.internal.os.LongMultiStateCounter");

        // Invalid int16 value
        data.writeInt(0x10000);     // stateCount
        for (int i = 0; i < 0x10000; ++i) {
            data.writeLong(0);
        }

        backPatchLength(data, lazyValueLenPos, lazyValueStart);
        backPatchLength(data, bundleLenPos, bundleStart);
        data.setDataPosition(0);

        assertThrows(BadParcelableException.class,
                () -> data.readBundle().getParcelable("key", LongMultiStateCounter.class));
    }

    private static void backPatchLength(Parcel parcel, int lengthPos, int startPos) {
        int endPos = parcel.dataPosition();
        parcel.setDataPosition(lengthPos);
        parcel.writeInt(endPos - startPos);
        parcel.setDataPosition(endPos);
    }
}
