/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.display.utils;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.ContextWrapper;
import android.content.res.Resources;
import android.util.TypedValue;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AmbientFilterTest {
    private ContextWrapper mContextSpy;
    private Resources mResourcesSpy;
    private static String TAG = "AmbientFilterTest";

    @Before
    public void setUp() throws Exception {
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);
    }

    @Test
    public void testBrightnessFilter_ZeroIntercept() throws Exception {
        final int horizon = 5 * 1000;
        final int time_start = 30 * 1000;
        final float intercept = 0.0f;
        final int prediction_time = 100;  // Hardcoded in AmbientFilter: prediction of how long the
                                          // latest prediction will last before a new prediction.
        setMockValues(mResourcesSpy, horizon, intercept);
        AmbientFilter filter = AmbientFilterFactory.createBrightnessFilter(TAG, mResourcesSpy);

        // Add first value and verify
        filter.addValue(time_start, 30);
        assertEquals(30, filter.getEstimate(time_start + prediction_time), 0.001);

        // Add second value and verify that they are being averaged:
        filter.addValue(time_start + prediction_time, 40);
        // We check immediately after the value is added to verify that the weight of the
        // prediction time is being correctly applied to the recent value correctly.
        // In this case (time is in seconds so 100ms = 0.1s):
        //    weight 1 (w1) = (0.5*0.1^2 - 0.5*0^2) = 0.005
        //    weight 2 (w2) = (0.5*0.2^2 - 0.5*0.1^2) = 0.015
        //    w_t = w1 + w2 = 0.02
        //    total = w1 * 30 + w2 * 40 = 0.75
        //    estimate = total / w_t = 0.75 / 0.02 = 37.5
        assertEquals(37.5, filter.getEstimate(time_start + prediction_time), 0.001);

        // Add a third value to push the first value off of the buffer.
        filter.addValue(time_start + horizon + prediction_time, 50);
        assertEquals(40.38846f, filter.getEstimate(time_start + horizon + prediction_time), 0.001);
    }

    @Test
    public void testBrightnessFilter_WithIntercept() throws Exception {
        final int horizon = 5 * 1000;
        final int time_start = 30 * 1000;
        final float intercept = 10f;
        final int prediction_time = 100;

        setMockValues(mResourcesSpy, horizon, intercept);
        AmbientFilter filter = AmbientFilterFactory.createBrightnessFilter(TAG, mResourcesSpy);

        // Add first value and verify
        filter.addValue(time_start, 30);
        assertEquals(30, filter.getEstimate(time_start + prediction_time), 0.001);

        // Add second value and verify that they are being averaged:
        filter.addValue(time_start + prediction_time, 40);
        // We check immediately after the value is added to verify that the weight of the
        // prediction time is being correctly applied to the recent value correctly.
        // In this case (time is in seconds so 100ms = 0.1s):
        //    weight 1 (w1) = (0.5*0.1^2 + 0.1*100) - (0.5*0^2 + 0*100) = 1.005
        //    weight 2 (w2) = (0.5*0.2^2 + 0.2*100) - (0.5*0.1^2 + 0.1*100) = 1.015
        //    w_t = w1 + w2 = 2.02
        //    total = w1 * 30 + w2 * 40 = 70.75
        //    estimate = total / w_t = 70.75 / 2.02 = 35.024752475
        assertEquals(35.02475f, filter.getEstimate(time_start + prediction_time), 0.001);

        // Add a third value to push the first value off of the buffer.
        filter.addValue(time_start + horizon + prediction_time, 50);
        assertEquals(40.23513f, filter.getEstimate(time_start + horizon + prediction_time), 0.001);
    }

    private void setMockValues(Resources resources, int horizon, float intercept) {
        doAnswer(invocation -> {
            TypedValue value = (TypedValue) invocation.getArguments()[1];
            value.type = TypedValue.TYPE_FLOAT;
            value.data = Float.floatToRawIntBits(intercept);
            return null;
        }).when(mResourcesSpy).getValue(
                eq(com.android.internal.R.dimen
                .config_displayWhiteBalanceBrightnessFilterIntercept),
                any(TypedValue.class), eq(true));
        when(mResourcesSpy.getInteger(
                com.android.internal.R.integer
                .config_displayWhiteBalanceBrightnessFilterHorizon)).thenReturn(horizon);
    }
}
