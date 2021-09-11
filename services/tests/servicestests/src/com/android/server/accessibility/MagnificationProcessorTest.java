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

package com.android.server.accessibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Region;

import com.android.server.accessibility.magnification.FullScreenMagnificationController;
import com.android.server.accessibility.magnification.MagnificationController;
import com.android.server.accessibility.magnification.MagnificationProcessor;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


/**
 * Tests for the {@link MagnificationProcessor}
 */
public class MagnificationProcessorTest {

    private static final int TEST_DISPLAY = 0;

    private MagnificationProcessor mMagnificationProcessor;
    @Mock
    private MagnificationController mMockMagnificationController;
    @Mock
    private FullScreenMagnificationController mMockFullScreenMagnificationController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mMockMagnificationController.getFullScreenMagnificationController()).thenReturn(
                mMockFullScreenMagnificationController);
        mMagnificationProcessor = new MagnificationProcessor(mMockMagnificationController);
    }

    @Test
    public void getScale() {
        final float result = 2;
        when(mMockFullScreenMagnificationController.getScale(TEST_DISPLAY)).thenReturn(result);

        float scale = mMagnificationProcessor.getScale(TEST_DISPLAY);

        assertEquals(scale, result, 0);
    }

    @Test
    public void getCenterX_canControlMagnification_returnCenterX() {
        final float result = 200;
        when(mMockFullScreenMagnificationController.getCenterX(TEST_DISPLAY)).thenReturn(result);

        float centerX = mMagnificationProcessor.getCenterX(
                TEST_DISPLAY,  /* canControlMagnification= */true);

        assertEquals(centerX, result, 0);
    }

    @Test
    public void getCenterY_canControlMagnification_returnCenterY() {
        final float result = 300;
        when(mMockFullScreenMagnificationController.getCenterY(TEST_DISPLAY)).thenReturn(result);

        float centerY = mMagnificationProcessor.getCenterY(
                TEST_DISPLAY,  /* canControlMagnification= */false);

        assertEquals(centerY, result, 0);
    }

    @Test
    public void getMagnificationRegion_canControlMagnification_returnRegion() {
        final Region region = new Region(10, 20, 100, 200);
        mMagnificationProcessor.getMagnificationRegion(TEST_DISPLAY,
                region,  /* canControlMagnification= */true);

        verify(mMockFullScreenMagnificationController).getMagnificationRegion(eq(TEST_DISPLAY),
                eq(region));
    }

    @Test
    public void getMagnificationRegion_notRegistered_shouldRegisterThenUnregister() {
        final Region region = new Region(10, 20, 100, 200);
        doAnswer((invocation) -> {
            ((Region) invocation.getArguments()[1]).set(region);
            return null;
        }).when(mMockFullScreenMagnificationController).getMagnificationRegion(eq(TEST_DISPLAY),
                any());
        when(mMockFullScreenMagnificationController.isRegistered(TEST_DISPLAY)).thenReturn(false);

        final Region result = new Region();
        mMagnificationProcessor.getMagnificationRegion(TEST_DISPLAY,
                result, /* canControlMagnification= */true);
        assertEquals(region, result);
        verify(mMockFullScreenMagnificationController).register(TEST_DISPLAY);
        verify(mMockFullScreenMagnificationController).unregister(TEST_DISPLAY);
    }

    @Test
    public void getMagnificationCenterX_notRegistered_shouldRegisterThenUnregister() {
        final float centerX = 480.0f;
        when(mMockFullScreenMagnificationController.getCenterX(TEST_DISPLAY)).thenReturn(centerX);
        when(mMockFullScreenMagnificationController.isRegistered(TEST_DISPLAY)).thenReturn(false);

        final float result = mMagnificationProcessor.getCenterX(
                TEST_DISPLAY,  /* canControlMagnification= */ true);
        assertEquals(centerX, result, 0);
        verify(mMockFullScreenMagnificationController).register(TEST_DISPLAY);
        verify(mMockFullScreenMagnificationController).unregister(TEST_DISPLAY);
    }

    @Test
    public void getMagnificationCenterY_notRegistered_shouldRegisterThenUnregister() {
        final float centerY = 640.0f;
        when(mMockFullScreenMagnificationController.getCenterY(TEST_DISPLAY)).thenReturn(centerY);
        when(mMockFullScreenMagnificationController.isRegistered(TEST_DISPLAY)).thenReturn(false);

        final float result = mMagnificationProcessor.getCenterY(
                TEST_DISPLAY,  /* canControlMagnification= */ true);
        assertEquals(centerY, result, 0);
        verify(mMockFullScreenMagnificationController).register(TEST_DISPLAY);
        verify(mMockFullScreenMagnificationController).unregister(TEST_DISPLAY);
    }

    @Test
    public void setMagnificationScaleAndCenter_notRegistered_shouldRegister() {
        final int serviceId = 42;
        final float scale = 1.8f;
        final float centerX = 50.5f;
        final float centerY = 100.5f;
        when(mMockFullScreenMagnificationController.setScaleAndCenter(TEST_DISPLAY,
                scale, centerX, centerY, true, serviceId)).thenReturn(true);
        when(mMockFullScreenMagnificationController.isRegistered(TEST_DISPLAY)).thenReturn(false);

        final boolean result = mMagnificationProcessor.setScaleAndCenter(
                TEST_DISPLAY, scale, centerX, centerY, true, serviceId);
        assertTrue(result);
        verify(mMockFullScreenMagnificationController).register(TEST_DISPLAY);
    }
}
