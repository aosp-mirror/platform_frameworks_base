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

package com.android.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

public class HighBrightnessModeMetadataMapperTest {

    private HighBrightnessModeMetadataMapper mHighBrightnessModeMetadataMapper;

    @Before
    public void setUp() {
        mHighBrightnessModeMetadataMapper = new HighBrightnessModeMetadataMapper();
    }

    @Test
    public void testGetHighBrightnessModeMetadata() {
        // Display device is null
        final LogicalDisplay display = mock(LogicalDisplay.class);
        when(display.getPrimaryDisplayDeviceLocked()).thenReturn(null);
        assertNull(mHighBrightnessModeMetadataMapper.getHighBrightnessModeMetadataLocked(display));

        // No HBM metadata stored for this display yet
        final DisplayDevice device = mock(DisplayDevice.class);
        when(display.getPrimaryDisplayDeviceLocked()).thenReturn(device);
        HighBrightnessModeMetadata hbmMetadata =
                mHighBrightnessModeMetadataMapper.getHighBrightnessModeMetadataLocked(display);
        assertTrue(hbmMetadata.getHbmEventQueue().isEmpty());
        assertTrue(hbmMetadata.getRunningStartTimeMillis() < 0);

        // Modify the metadata
        long startTimeMillis = 100;
        long endTimeMillis = 200;
        long setTime = 300;
        hbmMetadata.addHbmEvent(new HbmEvent(startTimeMillis, endTimeMillis));
        hbmMetadata.setRunningStartTimeMillis(setTime);
        hbmMetadata =
                mHighBrightnessModeMetadataMapper.getHighBrightnessModeMetadataLocked(display);
        assertEquals(1, hbmMetadata.getHbmEventQueue().size());
        assertEquals(startTimeMillis,
                hbmMetadata.getHbmEventQueue().getFirst().getStartTimeMillis());
        assertEquals(endTimeMillis, hbmMetadata.getHbmEventQueue().getFirst().getEndTimeMillis());
        assertEquals(setTime, hbmMetadata.getRunningStartTimeMillis());
    }
}
