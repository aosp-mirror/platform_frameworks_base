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

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LongArrayMultiStateCounterTest {

    @Test
    public void setStateAndUpdateValue() {
        LongArrayMultiStateCounter.LongArrayContainer longArrayContainer =
                new LongArrayMultiStateCounter.LongArrayContainer(4);
        LongArrayMultiStateCounter counter = new LongArrayMultiStateCounter(2, 4, 0, 1000);
        counter.setState(1, 2000);
        counter.setState(0, 4000);
        longArrayContainer.setValues(new long[]{100, 200, 300, 400});
        counter.updateValues(longArrayContainer, 9000);
        counter.getCounts(longArrayContainer, 0);

        long[] result = new long[4];
        longArrayContainer.getValues(result);
        assertThat(result).isEqualTo(new long[]{75, 150, 225, 300});

        counter.getCounts(longArrayContainer, 1);
        longArrayContainer.getValues(result);
        assertThat(result).isEqualTo(new long[]{25, 50, 75, 100});

        assertThat(counter.toString()).isEqualTo(
                "currentState: 0 lastStateChangeTimestamp: 9000 lastUpdateTimestamp: 9000 states:"
                        + " [0: time: 0 counter: { 75, 150, 225, 300}"
                        + ", 1: time: 0 counter: { 25, 50, 75, 100}]");
    }
}
