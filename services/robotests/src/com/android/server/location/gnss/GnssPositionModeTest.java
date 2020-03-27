/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.gnss;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashSet;

/**
 * Unit tests for {@link GnssPositionMode}.
 */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class GnssPositionModeTest {

    private GnssPositionMode positionMode1 = createGnssPositionMode(0, 1000);
    private GnssPositionMode positionMode2 = createGnssPositionMode(0, 1000);
    private GnssPositionMode positionMode3 = createGnssPositionMode(1, 1000);

    @Test
    public void testHashCode() {
        assertThat(positionMode1.hashCode()).isEqualTo(positionMode2.hashCode());
        assertThat(positionMode1.hashCode()).isNotEqualTo(positionMode3.hashCode());
        assertThat(positionMode1.hashCode()).isNotEqualTo(positionMode3.hashCode());

        HashSet<Integer> hashSet = new HashSet<>();
        hashSet.add(positionMode1.hashCode());
        hashSet.add(positionMode2.hashCode());
        assertThat(hashSet.size()).isEqualTo(1);
        hashSet.add(positionMode3.hashCode());
        assertThat(hashSet.size()).isEqualTo(2);
    }

    @Test
    public void checkIfEqualsImpliesSameHashCode() {
        assertTEqualsImpliesSameHashCode(positionMode1, positionMode2);
        assertTEqualsImpliesSameHashCode(positionMode2, positionMode3);
    }

    private void assertTEqualsImpliesSameHashCode(GnssPositionMode mode1, GnssPositionMode mode2) {
        if (mode1.equals(mode2)) {
            assertThat(mode1.hashCode()).isEqualTo(mode2.hashCode());
        }
    }

    private GnssPositionMode createGnssPositionMode(int mode, int minInterval) {
        return new GnssPositionMode(mode, 0, minInterval, 0, 0, true);
    }
}
