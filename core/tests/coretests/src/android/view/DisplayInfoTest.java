/*
 * Copyright (C) 2023 The Android Open Source Project
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


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DisplayInfoTest {
    private static final float FLOAT_EQUAL_DELTA = 0.0001f;

    @Test
    public void testDefaultDisplayInfosAreEqual() {
        DisplayInfo displayInfo1 = new DisplayInfo();
        DisplayInfo displayInfo2 = new DisplayInfo();

        assertTrue(displayInfo1.equals(displayInfo2));
    }

    @Test
    public void testDefaultDisplayInfoRefreshRateIs0() {
        DisplayInfo displayInfo = new DisplayInfo();

        assertEquals(0, displayInfo.getRefreshRate(), FLOAT_EQUAL_DELTA);
    }

    @Test
    public void testRefreshRateOverride() {
        DisplayInfo displayInfo = new DisplayInfo();

        displayInfo.refreshRateOverride = 50;

        assertEquals(50, displayInfo.getRefreshRate(), FLOAT_EQUAL_DELTA);

    }

    @Test
    public void testRefreshRateOverride_keepsDisplyInfosEqual() {
        Display.Mode mode = new Display.Mode(
                /*modeId=*/1, /*width=*/1000, /*height=*/1000, /*refreshRate=*/120);
        DisplayInfo displayInfo1 = new DisplayInfo();
        setSupportedMode(displayInfo1, mode);

        DisplayInfo displayInfo2 = new DisplayInfo();
        setSupportedMode(displayInfo2, mode);
        displayInfo2.refreshRateOverride = 120;

        assertTrue(displayInfo1.equals(displayInfo2));
    }

    @Test
    public void testRefreshRateOverride_makeDisplayInfosDifferent() {
        Display.Mode mode = new Display.Mode(
                /*modeId=*/1, /*width=*/1000, /*height=*/1000, /*refreshRate=*/120);
        DisplayInfo displayInfo1 = new DisplayInfo();
        setSupportedMode(displayInfo1, mode);

        DisplayInfo displayInfo2 = new DisplayInfo();
        setSupportedMode(displayInfo2, mode);
        displayInfo2.refreshRateOverride = 90;

        assertFalse(displayInfo1.equals(displayInfo2));
    }

    private void setSupportedMode(DisplayInfo info, Display.Mode mode) {
        info.supportedModes = new Display.Mode[]{mode};
        info.modeId = mode.getModeId();
    }

}
