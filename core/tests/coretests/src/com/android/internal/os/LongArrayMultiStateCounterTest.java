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
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@IgnoreUnderRavenwood(blockedBy = LongArrayMultiStateCounter.class)
public class LongArrayMultiStateCounterTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    public void setStateAndUpdateValue() {
        LongArrayMultiStateCounter counter = new LongArrayMultiStateCounter(2, 4);

        updateValue(counter, new long[]{0, 0, 0, 0}, 1000);
        counter.setState(0, 1000);
        counter.setState(1, 2000);
        counter.setState(0, 4000);
        updateValue(counter, new long[]{100, 200, 300, 400}, 9000);

        assertCounts(counter, 0, new long[]{75, 150, 225, 300});
        assertCounts(counter, 1, new long[]{25, 50, 75, 100});

        assertThat(counter.toString()).isEqualTo(
                "[0: {75, 150, 225, 300}, 1: {25, 50, 75, 100}] updated: 9000 currentState: 0");
    }

    @Test
    public void setValue() {
        LongArrayMultiStateCounter counter = new LongArrayMultiStateCounter(2, 4);

        counter.setValues(0, new long[]{1, 2, 3, 4});
        counter.setValues(1, new long[]{5, 6, 7, 8});
        assertCounts(counter, 0, new long[]{1, 2, 3, 4});
        assertCounts(counter, 1, new long[]{5, 6, 7, 8});
    }

    @Test
    public void setEnabled() {
        LongArrayMultiStateCounter counter = new LongArrayMultiStateCounter(2, 4);
        counter.setState(0, 1000);
        updateValue(counter, new long[]{0, 0, 0, 0}, 1000);
        updateValue(counter, new long[]{100, 200, 300, 400}, 2000);

        assertCounts(counter, 0, new long[]{100, 200, 300, 400});

        counter.setEnabled(false, 3000);

        // Partially included, because the counter is disabled after the previous update
        updateValue(counter, new long[]{200, 300, 400, 500}, 4000);

        // Count only 50%, because the counter was disabled for 50% of the time
        assertCounts(counter, 0, new long[]{150, 250, 350, 450});

        // Not counted because the counter is disabled
        updateValue(counter, new long[]{250, 350, 450, 550}, 5000);

        counter.setEnabled(true, 6000);

        updateValue(counter, new long[]{300, 400, 500, 600}, 7000);

        // Again, take 50% of the delta
        assertCounts(counter, 0, new long[]{175, 275, 375, 475});
    }

    @Test
    public void reset() {
        LongArrayMultiStateCounter counter = new LongArrayMultiStateCounter(2, 4);
        counter.setState(0, 1000);
        updateValue(counter, new long[]{0, 0, 0, 0}, 1000);
        updateValue(counter, new long[]{100, 200, 300, 400}, 2000);

        assertCounts(counter, 0, new long[]{100, 200, 300, 400});

        counter.reset();

        assertCounts(counter, 0, new long[]{0, 0, 0, 0});

        updateValue(counter, new long[]{200, 300, 400, 500}, 3000);
        updateValue(counter, new long[]{300, 400, 500, 600}, 4000);

        assertCounts(counter, 0, new long[]{100, 100, 100, 100});
    }

    @Test
    public void parceling() {
        LongArrayMultiStateCounter counter = new LongArrayMultiStateCounter(2, 4);
        updateValue(counter, new long[]{0, 0, 0, 0}, 1000);
        counter.setState(0, 1000);
        updateValue(counter, new long[]{100, 200, 300, 400}, 2000);
        counter.setState(1, 2000);
        updateValue(counter, new long[]{101, 202, 304, 408}, 3000);

        assertCounts(counter, 0, new long[]{100, 200, 300, 400});
        assertCounts(counter, 1, new long[]{1, 2, 4, 8});

        Parcel parcel = Parcel.obtain();
        counter.writeToParcel(parcel, 0);
        byte[] bytes = parcel.marshall();
        parcel.recycle();

        parcel = Parcel.obtain();
        parcel.unmarshall(bytes, 0, bytes.length);
        parcel.setDataPosition(0);

        LongArrayMultiStateCounter newCounter =
                LongArrayMultiStateCounter.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        assertCounts(newCounter, 0, new long[]{100, 200, 300, 400});
        assertCounts(newCounter, 1, new long[]{1, 2, 4, 8});

        // ==== Verify that the counter keeps accumulating after unparceling.

        // State, last update timestamp and current counts are undefined at this point.
        newCounter.setState(0, 100);
        updateValue(newCounter, new long[]{300, 400, 500, 600}, 100);

        // A new base state and counters are established; we can continue accumulating deltas
        updateValue(newCounter, new long[]{316, 432, 564, 728}, 200);

        assertCounts(newCounter, 0, new long[]{116, 232, 364, 528});
    }

    private void updateValue(LongArrayMultiStateCounter counter, long[] values, int timestamp) {
        LongArrayMultiStateCounter.LongArrayContainer container =
                new LongArrayMultiStateCounter.LongArrayContainer(values.length);
        container.setValues(values);
        counter.updateValues(container, timestamp);
    }

    private void assertCounts(LongArrayMultiStateCounter counter, int state, long[] expected) {
        LongArrayMultiStateCounter.LongArrayContainer container =
                new LongArrayMultiStateCounter.LongArrayContainer(expected.length);
        long[] counts = new long[expected.length];
        counter.getCounts(container, state);
        container.getValues(counts);
        assertThat(counts).isEqualTo(expected);
    }

    @Test
    public void createFromBadParcel() {
        // Check we don't crash the runtime if the Parcel data is bad (b/243434675).
        Parcel parcel = Parcel.obtain();
        parcel.writeInt(2);
        parcel.setDataPosition(0);
        assertThrows(RuntimeException.class,
                () -> LongArrayMultiStateCounter.CREATOR.createFromParcel(parcel));
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
        data.writeString("com.android.internal.os.LongArrayMultiStateCounter");

        // Invalid int16 value
        data.writeInt(0x10000);     // stateCount
        data.writeInt(10);          // arrayLength
        for (int i = 0; i < 0x10000; ++i) {
            data.writeLong(0);
        }

        backPatchLength(data, lazyValueLenPos, lazyValueStart);
        backPatchLength(data, bundleLenPos, bundleStart);
        data.setDataPosition(0);

        assertThrows(BadParcelableException.class,
                () -> data.readBundle().getParcelable("key", LongArrayMultiStateCounter.class));
    }

    private static void backPatchLength(Parcel parcel, int lengthPos, int startPos) {
        int endPos = parcel.dataPosition();
        parcel.setDataPosition(lengthPos);
        parcel.writeInt(endPos - startPos);
        parcel.setDataPosition(endPos);
    }

    @Test
    public void combineValues() {
        long[] values = new long[] {0, 1, 2, 3, 42};
        LongArrayMultiStateCounter.LongArrayContainer container =
                new LongArrayMultiStateCounter.LongArrayContainer(values.length);
        container.setValues(values);

        long[] out = new long[3];
        int[] indexes = {2, 1, 1, 0, 0};
        boolean nonZero = container.combineValues(out, indexes);
        assertThat(nonZero).isTrue();
        assertThat(out).isEqualTo(new long[]{45, 3, 0});

        // All zeros
        container.setValues(new long[]{0, 0, 0, 0, 0});
        nonZero = container.combineValues(out, indexes);
        assertThat(nonZero).isFalse();
        assertThat(out).isEqualTo(new long[]{0, 0, 0});

        // Index out of range
        IndexOutOfBoundsException e1 = assertThrows(
                IndexOutOfBoundsException.class,
                () -> container.combineValues(out, new int[]{0, 1, -1, 0, 0}));
        assertThat(e1.getMessage()).isEqualTo("Index -1 is out of bounds: [0, 2]");
        IndexOutOfBoundsException e2 = assertThrows(IndexOutOfBoundsException.class,
                () -> container.combineValues(out, new int[]{0, 1, 4, 0, 0}));
        assertThat(e2.getMessage()).isEqualTo("Index 4 is out of bounds: [0, 2]");
    }
}
