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

package com.android.server.display.brightness.clamper;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.os.PowerManager;

import androidx.test.filters.SmallTest;

import com.android.server.display.config.HdrBrightnessData;
import com.android.server.testutils.OffsettableClock;
import com.android.server.testutils.TestHandler;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Map;

@SmallTest
public class HdrClamperTest {

    public static final float FLOAT_TOLERANCE = 0.0001f;

    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();

    @Mock
    private BrightnessClamperController.ClamperChangeListener mMockListener;

    OffsettableClock mClock = new OffsettableClock.Stopped();

    private final TestHandler mTestHandler = new TestHandler(null, mClock);


    private HdrClamper mHdrClamper;


    @Before
    public void setUp() {
        mHdrClamper = new HdrClamper(mMockListener, mTestHandler);
        configureClamper();
    }

    @Test
    public void testClamper_AmbientLuxChangesAboveLimit() {
        mHdrClamper.onAmbientLuxChange(500);

        assertFalse(mTestHandler.hasMessagesOrCallbacks());
        assertEquals(PowerManager.BRIGHTNESS_MAX, mHdrClamper.getMaxBrightness(), FLOAT_TOLERANCE);
    }

    @Test
    public void testClamper_AmbientLuxChangesBelowLimit() {
        mHdrClamper.onAmbientLuxChange(499);

        assertTrue(mTestHandler.hasMessagesOrCallbacks());
        TestHandler.MsgInfo msgInfo = mTestHandler.getPendingMessages().peek();
        assertEquals(2000, msgInfo.sendTime);
        assertEquals(PowerManager.BRIGHTNESS_MAX, mHdrClamper.getMaxBrightness(), FLOAT_TOLERANCE);

        mClock.fastForward(2000);
        mTestHandler.timeAdvance();
        assertEquals(0.6f, mHdrClamper.getMaxBrightness(), FLOAT_TOLERANCE);
    }

    @Test
    public void testClamper_AmbientLuxChangesBelowLimit_ThenFastAboveLimit() {
        mHdrClamper.onAmbientLuxChange(499);
        mHdrClamper.onAmbientLuxChange(500);

        assertFalse(mTestHandler.hasMessagesOrCallbacks());
        assertEquals(PowerManager.BRIGHTNESS_MAX, mHdrClamper.getMaxBrightness(), FLOAT_TOLERANCE);
    }

    @Test
    public void testClamper_AmbientLuxChangesBelowLimit_ThenSlowlyAboveLimit() {
        mHdrClamper.onAmbientLuxChange(499);
        mClock.fastForward(2000);
        mTestHandler.timeAdvance();

        mHdrClamper.onAmbientLuxChange(500);

        assertTrue(mTestHandler.hasMessagesOrCallbacks());
        TestHandler.MsgInfo msgInfo = mTestHandler.getPendingMessages().peek();
        assertEquals(3000, msgInfo.sendTime); // 2000 + 1000

        mClock.fastForward(1000);
        mTestHandler.timeAdvance();
        assertEquals(PowerManager.BRIGHTNESS_MAX, mHdrClamper.getMaxBrightness(), FLOAT_TOLERANCE);
    }

    private void configureClamper() {
        HdrBrightnessData data = new HdrBrightnessData(
                Map.of(500f, 0.6f),
                /* brightnessIncreaseDebounceMillis= */ 1000,
                /* brightnessIncreaseDurationMillis= */ 1500,
                /* brightnessDecreaseDebounceMillis= */ 2000,
                /* brightnessDecreaseDurationMillis= */2500
        );
        mHdrClamper.resetHdrConfig(data);
    }
}
