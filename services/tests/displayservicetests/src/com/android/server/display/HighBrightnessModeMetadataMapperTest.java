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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import com.android.server.display.config.HighBrightnessModeData;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class HighBrightnessModeMetadataMapperTest {

    @Mock
    private LogicalDisplay mDisplayMock;

    @Mock
    private DisplayDevice mDeviceMock;

    @Mock
    private DisplayDeviceConfig mDdcMock;

    @Mock
    private HighBrightnessModeData mHbmDataMock;

    private HighBrightnessModeMetadataMapper mHighBrightnessModeMetadataMapper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mDisplayMock.getPrimaryDisplayDeviceLocked()).thenReturn(mDeviceMock);
        when(mDeviceMock.getDisplayDeviceConfig()).thenReturn(mDdcMock);
        when(mDdcMock.getHighBrightnessModeData()).thenReturn(mHbmDataMock);
        mHighBrightnessModeMetadataMapper = new HighBrightnessModeMetadataMapper();
    }

    @Test
    public void testGetHighBrightnessModeMetadata_NoDisplayDevice() {
        when(mDisplayMock.getPrimaryDisplayDeviceLocked()).thenReturn(null);
        assertNull(mHighBrightnessModeMetadataMapper
                .getHighBrightnessModeMetadataLocked(mDisplayMock));
    }

    @Test
    public void testGetHighBrightnessModeMetadata_NoHBMData() {
        when(mDdcMock.getHighBrightnessModeData()).thenReturn(null);
        assertNull(mHighBrightnessModeMetadataMapper
                .getHighBrightnessModeMetadataLocked(mDisplayMock));
    }

    @Test
    public void testGetHighBrightnessModeMetadata_NewDisplay() {
        HighBrightnessModeMetadata hbmMetadata =
                mHighBrightnessModeMetadataMapper.getHighBrightnessModeMetadataLocked(mDisplayMock);
        assertNotNull(hbmMetadata);
        assertTrue(hbmMetadata.getHbmEventQueue().isEmpty());
        assertTrue(hbmMetadata.getRunningStartTimeMillis() < 0);
    }

    @Test
    public void testGetHighBrightnessModeMetadata_Modify() {
        HighBrightnessModeMetadata hbmMetadata =
                mHighBrightnessModeMetadataMapper.getHighBrightnessModeMetadataLocked(mDisplayMock);
        assertNotNull(hbmMetadata);
        assertTrue(hbmMetadata.getHbmEventQueue().isEmpty());
        assertTrue(hbmMetadata.getRunningStartTimeMillis() < 0);

        // Modify the metadata
        long startTimeMillis = 100;
        long endTimeMillis = 200;
        long setTime = 300;
        hbmMetadata.addHbmEvent(new HbmEvent(startTimeMillis, endTimeMillis));
        hbmMetadata.setRunningStartTimeMillis(setTime);

        hbmMetadata =
                mHighBrightnessModeMetadataMapper.getHighBrightnessModeMetadataLocked(mDisplayMock);

        assertEquals(1, hbmMetadata.getHbmEventQueue().size());
        assertEquals(startTimeMillis,
                hbmMetadata.getHbmEventQueue().getFirst().getStartTimeMillis());
        assertEquals(endTimeMillis, hbmMetadata.getHbmEventQueue().getFirst().getEndTimeMillis());
        assertEquals(setTime, hbmMetadata.getRunningStartTimeMillis());
    }
}
