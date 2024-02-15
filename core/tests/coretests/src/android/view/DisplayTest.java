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

package android.view;

import static com.google.common.truth.Truth.assertWithMessage;

import android.util.DebugUtils;

import androidx.test.filters.SmallTest;

import com.android.internal.util.ArrayUtils;

import org.junit.Test;

import java.util.function.IntFunction;

@SmallTest
public class DisplayTest {
    private static final int[] DISPLAY_STATES = {
            Display.STATE_UNKNOWN,
            Display.STATE_OFF,
            Display.STATE_ON,
            Display.STATE_DOZE,
            Display.STATE_DOZE_SUSPEND,
            Display.STATE_VR,
            Display.STATE_ON_SUSPEND
    };

    @Test
    public void isSuspendedState() {
        assertOnlyTrueForStates(
                Display::isSuspendedState,
                Display.STATE_OFF,
                Display.STATE_DOZE_SUSPEND,
                Display.STATE_ON_SUSPEND
        );
    }

    @Test
    public void isDozeState() {
        assertOnlyTrueForStates(
                Display::isDozeState,
                Display.STATE_DOZE,
                Display.STATE_DOZE_SUSPEND
        );
    }

    @Test
    public void isActiveState() {
        assertOnlyTrueForStates(
                Display::isActiveState,
                Display.STATE_ON,
                Display.STATE_VR
        );
    }

    @Test
    public void isOffState() {
        assertOnlyTrueForStates(
                Display::isOffState,
                Display.STATE_OFF
        );
    }

    @Test
    public void isOnState() {
        assertOnlyTrueForStates(
                Display::isOnState,
                Display.STATE_ON,
                Display.STATE_VR,
                Display.STATE_ON_SUSPEND
        );
    }

    private void assertOnlyTrueForStates(IntFunction<Boolean> function, int... trueStates) {
        for (int state : DISPLAY_STATES) {
            boolean actual = function.apply(state);
            boolean expected = ArrayUtils.contains(trueStates, state);
            assertWithMessage("Unexpected return for Display.STATE_"
                    + DebugUtils.constantToString(Display.class, "STATE_", state))
                    .that(actual).isEqualTo(expected);
        }
    }
}
