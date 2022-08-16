/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.dreams.complication;

import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ComplicationLayoutParamsTest extends SysuiTestCase {
    /**
     * Ensures ComplicationLayoutParams cannot be constructed with improper position or direction.
     */
    @Test
    public void testPositionValidation() {
        final HashSet<Integer> invalidCombinations = new HashSet(Arrays.asList(
                ComplicationLayoutParams.POSITION_BOTTOM | ComplicationLayoutParams.POSITION_TOP,
                ComplicationLayoutParams.POSITION_END | ComplicationLayoutParams.POSITION_START
        ));

        final int allPositions = ComplicationLayoutParams.POSITION_TOP
                | ComplicationLayoutParams.POSITION_START
                | ComplicationLayoutParams.POSITION_END
                | ComplicationLayoutParams.POSITION_BOTTOM;

        final HashSet<Integer> allDirections = new HashSet(Arrays.asList(
                ComplicationLayoutParams.DIRECTION_DOWN,
                ComplicationLayoutParams.DIRECTION_UP,
                ComplicationLayoutParams.DIRECTION_START,
                ComplicationLayoutParams.DIRECTION_END
        ));

        final HashMap<Integer, Integer> invalidDirections = new HashMap<>();
        invalidDirections.put(ComplicationLayoutParams.DIRECTION_DOWN,
                ComplicationLayoutParams.POSITION_BOTTOM);
        invalidDirections.put(ComplicationLayoutParams.DIRECTION_UP,
                ComplicationLayoutParams.POSITION_TOP);
        invalidDirections.put(ComplicationLayoutParams.DIRECTION_START,
                ComplicationLayoutParams.POSITION_START);
        invalidDirections.put(ComplicationLayoutParams.DIRECTION_END,
                ComplicationLayoutParams.POSITION_END);


        for (int position = 0; position <= allPositions; ++position) {
            boolean properPosition = position != 0;
            if (properPosition) {
                for (Integer combination : invalidCombinations) {
                    if ((combination & position) == combination) {
                        properPosition = false;
                    }
                }
            }
            boolean exceptionEncountered = false;
            for (Integer direction : allDirections) {
                final int invalidPosition = invalidDirections.get(direction);
                final boolean properDirection = (invalidPosition & position) != invalidPosition;

                try {
                    new ComplicationLayoutParams(
                            100,
                            100,
                            position,
                            direction,
                            0);
                } catch (Exception e) {
                    exceptionEncountered = true;
                }

                assertThat((properPosition && properDirection) || exceptionEncountered).isTrue();
            }
        }
    }

    /**
     * Ensures ComplicationLayoutParams is properly duplicated on copy construction.
     */
    @Test
    public void testCopyConstruction() {
        final ComplicationLayoutParams params = new ComplicationLayoutParams(
                100,
                100,
                ComplicationLayoutParams.POSITION_TOP,
                ComplicationLayoutParams.DIRECTION_DOWN,
                3);
        final ComplicationLayoutParams copy = new ComplicationLayoutParams(params);

        assertThat(copy.getDirection() == params.getDirection()).isTrue();
        assertThat(copy.getPosition() == params.getPosition()).isTrue();
        assertThat(copy.getWeight() == params.getWeight()).isTrue();
        assertThat(copy.height == params.height).isTrue();
        assertThat(copy.width == params.width).isTrue();
    }
}
