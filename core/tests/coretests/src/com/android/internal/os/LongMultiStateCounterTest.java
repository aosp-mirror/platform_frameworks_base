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

import android.os.Parcel;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LongMultiStateCounterTest {

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
}
