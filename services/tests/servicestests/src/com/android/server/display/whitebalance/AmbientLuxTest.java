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

package com.android.server.display.whitebalance;

import com.android.internal.R;
import com.android.server.display.utils.AmbientFilter;
import com.android.server.display.utils.AmbientFilterStubber;
import com.google.common.collect.ImmutableList;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import org.mockito.stubbing.Answer;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import android.content.ContextWrapper;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

@RunWith(JUnit4.class)
public final class AmbientLuxTest {
    private static final int AMBIENT_COLOR_TYPE = 20705;
    private static final String AMBIENT_COLOR_TYPE_STR = "colorSensoryDensoryDoc";
    private static final float LOW_LIGHT_AMBIENT_COLOR_TEMPERATURE = 5432.1f;
    private static final float HIGH_LIGHT_AMBIENT_COLOR_TEMPERATURE = 3456.7f;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Sensor mLightSensor;
    private Sensor mAmbientColorSensor;
    private ContextWrapper mContextSpy;
    private Resources mResourcesSpy;

    @Mock private SensorManager mSensorManagerMock;

    @Mock private TypedArray mBrightnesses;
    @Mock private TypedArray mBiases;
    @Mock private TypedArray mHighLightBrightnesses;
    @Mock private TypedArray mHighLightBiases;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLightSensor = createSensor(Sensor.TYPE_LIGHT, null);
        mAmbientColorSensor = createSensor(AMBIENT_COLOR_TYPE, AMBIENT_COLOR_TYPE_STR);
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));
        mResourcesSpy = spy(mContextSpy.getResources());
        when(mContextSpy.getResources()).thenReturn(mResourcesSpy);
        when(mSensorManagerMock.getDefaultSensor(Sensor.TYPE_LIGHT)).thenReturn(mLightSensor);
        final List<Sensor> sensorList = ImmutableList.of(mLightSensor, mAmbientColorSensor);
        when(mSensorManagerMock.getSensorList(Sensor.TYPE_ALL)).thenReturn(sensorList);
        when(mResourcesSpy.getString(
                R.string.config_displayWhiteBalanceColorTemperatureSensorName))
                .thenReturn(AMBIENT_COLOR_TYPE_STR);
        when(mResourcesSpy.getInteger(
                R.integer.config_displayWhiteBalanceDecreaseDebounce))
                .thenReturn(0);
        when(mResourcesSpy.getInteger(
                R.integer.config_displayWhiteBalanceIncreaseDebounce))
                .thenReturn(0);
        mockResourcesFloat(R.dimen.config_displayWhiteBalanceLowLightAmbientColorTemperature,
                LOW_LIGHT_AMBIENT_COLOR_TEMPERATURE);
        mockResourcesFloat(R.dimen.config_displayWhiteBalanceHighLightAmbientColorTemperature,
                HIGH_LIGHT_AMBIENT_COLOR_TEMPERATURE);
        when(mResourcesSpy.obtainTypedArray(
                R.array.config_displayWhiteBalanceAmbientColorTemperatures))
                .thenReturn(createTypedArray());
        when(mResourcesSpy.obtainTypedArray(
                R.array.config_displayWhiteBalanceDisplayColorTemperatures))
                .thenReturn(createTypedArray());

        when(mResourcesSpy.obtainTypedArray(
                R.array.config_displayWhiteBalanceLowLightAmbientBrightnesses))
                .thenReturn(mBrightnesses);
        when(mResourcesSpy.obtainTypedArray(
                R.array.config_displayWhiteBalanceLowLightAmbientBiases))
                .thenReturn(mBiases);
        when(mResourcesSpy.obtainTypedArray(
                R.array.config_displayWhiteBalanceHighLightAmbientBrightnesses))
                .thenReturn(mHighLightBrightnesses);
        when(mResourcesSpy.obtainTypedArray(
                R.array.config_displayWhiteBalanceHighLightAmbientBiases))
                .thenReturn(mHighLightBiases);
        mockThrottler();
    }

    @Test
    public void testNoSpline() throws Exception {
        setBrightnesses();
        setBiases();

        DisplayWhiteBalanceController controller =
                DisplayWhiteBalanceFactory.create(mHandler, mSensorManagerMock, mResourcesSpy);
        final float ambientColorTemperature = 8000.0f;
        setEstimatedColorTemperature(controller, ambientColorTemperature);
        controller.mBrightnessFilter = spy(new AmbientFilterStubber());

        for (float luxOverride = 0.1f; luxOverride <= 10000; luxOverride *= 10) {
            setEstimatedBrightnessAndUpdate(controller, luxOverride);
            assertEquals(controller.mPendingAmbientColorTemperature,
                    ambientColorTemperature, 0.001);
        }
    }

    @Test
    public void testSpline_OneSegment() throws Exception {
        final float lowerBrightness = 10.0f;
        final float upperBrightness = 50.0f;
        setBrightnesses(lowerBrightness, upperBrightness);
        setBiases(0.0f, 1.0f);

        DisplayWhiteBalanceController controller =
                DisplayWhiteBalanceFactory.create(mHandler, mSensorManagerMock, mResourcesSpy);
        final float ambientColorTemperature = 8000.0f;
        setEstimatedColorTemperature(controller, ambientColorTemperature);
        controller.mBrightnessFilter = spy(new AmbientFilterStubber());

        for (float t = 0.0f; t <= 1.0f; t += 0.1f) {
            setEstimatedBrightnessAndUpdate(controller,
                    mix(lowerBrightness, upperBrightness, t));
            assertEquals(controller.mPendingAmbientColorTemperature,
                    mix(LOW_LIGHT_AMBIENT_COLOR_TEMPERATURE, ambientColorTemperature, t), 0.001);
        }

        setEstimatedBrightnessAndUpdate(controller, 0.0f);
        assertEquals(controller.mPendingAmbientColorTemperature,
                LOW_LIGHT_AMBIENT_COLOR_TEMPERATURE, 0.001);

        setEstimatedBrightnessAndUpdate(controller, upperBrightness + 1.0f);
        assertEquals(controller.mPendingAmbientColorTemperature, ambientColorTemperature, 0.001);
    }

    @Test
    public void testSpline_TwoSegments() throws Exception {
        final float brightness0 = 10.0f;
        final float brightness1 = 50.0f;
        final float brightness2 = 60.0f;
        setBrightnesses(brightness0, brightness1, brightness2);
        final float bias0 = 0.0f;
        final float bias1 = 0.25f;
        final float bias2 = 1.0f;
        setBiases(bias0, bias1, bias2);

        DisplayWhiteBalanceController controller =
                DisplayWhiteBalanceFactory.create(mHandler, mSensorManagerMock, mResourcesSpy);
        final float ambientColorTemperature = 8000.0f;
        setEstimatedColorTemperature(controller, ambientColorTemperature);
        controller.mBrightnessFilter = spy(new AmbientFilterStubber());

        for (float t = 0.0f; t <= 1.0f; t += 0.1f) {
            float luxOverride = mix(brightness0, brightness1, t);
            setEstimatedBrightnessAndUpdate(controller, luxOverride);
            float bias = mix(bias0, bias1, t);
            assertEquals(controller.mPendingAmbientColorTemperature,
                    mix(LOW_LIGHT_AMBIENT_COLOR_TEMPERATURE, ambientColorTemperature, bias), 0.001);
        }

        for (float t = 0.0f; t <= 1.0f; t += 0.1f) {
            float luxOverride = mix(brightness1, brightness2, t);
            setEstimatedBrightnessAndUpdate(controller, luxOverride);
            float bias = mix(bias1, bias2, t);
            assertEquals(controller.mPendingAmbientColorTemperature,
                    mix(LOW_LIGHT_AMBIENT_COLOR_TEMPERATURE, ambientColorTemperature, bias), 0.001);
        }

        setEstimatedBrightnessAndUpdate(controller, 0.0f);
        assertEquals(controller.mPendingAmbientColorTemperature,
                LOW_LIGHT_AMBIENT_COLOR_TEMPERATURE, 0.001);

        setEstimatedBrightnessAndUpdate(controller, brightness2 + 1.0f);
        assertEquals(controller.mPendingAmbientColorTemperature, ambientColorTemperature, 0.001);
    }

    @Test
    public void testSpline_VerticalSegment() throws Exception {
        final float lowerBrightness = 10.0f;
        final float upperBrightness = 10.0f;
        setBrightnesses(lowerBrightness, upperBrightness);
        setBiases(0.0f, 1.0f);

        DisplayWhiteBalanceController controller =
                DisplayWhiteBalanceFactory.create(mHandler, mSensorManagerMock, mResourcesSpy);
        final float ambientColorTemperature = 8000.0f;
        setEstimatedColorTemperature(controller, ambientColorTemperature);
        controller.mBrightnessFilter = spy(new AmbientFilterStubber());

        setEstimatedBrightnessAndUpdate(controller, 0.0f);
        assertEquals(controller.mPendingAmbientColorTemperature,
                LOW_LIGHT_AMBIENT_COLOR_TEMPERATURE, 0.001);

        setEstimatedBrightnessAndUpdate(controller, upperBrightness + 1.0f);
        assertEquals(controller.mPendingAmbientColorTemperature, ambientColorTemperature, 0.001);
    }

    @Test
    public void testSpline_InvalidEndBias() throws Exception {
        setBrightnesses(10.0f, 1000.0f);
        setBiases(0.0f, 2.0f);

        DisplayWhiteBalanceController controller =
                DisplayWhiteBalanceFactory.create(mHandler, mSensorManagerMock, mResourcesSpy);
        final float ambientColorTemperature = 8000.0f;
        setEstimatedColorTemperature(controller, ambientColorTemperature);
        controller.mBrightnessFilter = spy(new AmbientFilterStubber());

        for (float luxOverride = 0.1f; luxOverride <= 10000; luxOverride *= 10) {
        setEstimatedBrightnessAndUpdate(controller, luxOverride);
        assertEquals(controller.mPendingAmbientColorTemperature,
                ambientColorTemperature, 0.001);
        }
    }

    @Test
    public void testSpline_InvalidBeginBias() throws Exception {
        setBrightnesses(10.0f, 1000.0f);
        setBiases(0.1f, 1.0f);

        DisplayWhiteBalanceController controller =
                DisplayWhiteBalanceFactory.create(mHandler, mSensorManagerMock, mResourcesSpy);
        final float ambientColorTemperature = 8000.0f;
        setEstimatedColorTemperature(controller, ambientColorTemperature);
        controller.mBrightnessFilter = spy(new AmbientFilterStubber());

        for (float luxOverride = 0.1f; luxOverride <= 10000; luxOverride *= 10) {
        setEstimatedBrightnessAndUpdate(controller, luxOverride);
        assertEquals(controller.mPendingAmbientColorTemperature,
                ambientColorTemperature, 0.001);
        }
    }

    @Test
    public void testSpline_OneSegmentHighLight() throws Exception {
        final float lowerBrightness = 10.0f;
        final float upperBrightness = 50.0f;
        setHighLightBrightnesses(lowerBrightness, upperBrightness);
        setHighLightBiases(0.0f, 1.0f);

        DisplayWhiteBalanceController controller =
                DisplayWhiteBalanceFactory.create(mHandler, mSensorManagerMock, mResourcesSpy);
        final float ambientColorTemperature = 8000.0f;
        setEstimatedColorTemperature(controller, ambientColorTemperature);
        controller.mBrightnessFilter = spy(new AmbientFilterStubber());

        for (float t = 0.0f; t <= 1.0f; t += 0.1f) {
            setEstimatedBrightnessAndUpdate(controller,
                    mix(lowerBrightness, upperBrightness, t));
            assertEquals(controller.mPendingAmbientColorTemperature,
                    mix(HIGH_LIGHT_AMBIENT_COLOR_TEMPERATURE, ambientColorTemperature, 1.0f - t),
                    0.001);
        }

        setEstimatedBrightnessAndUpdate(controller, upperBrightness + 1.0f);
        assertEquals(controller.mPendingAmbientColorTemperature,
                HIGH_LIGHT_AMBIENT_COLOR_TEMPERATURE, 0.001);

        setEstimatedBrightnessAndUpdate(controller, 0.0f);
        assertEquals(controller.mPendingAmbientColorTemperature, ambientColorTemperature, 0.001);
    }

    @Test
    public void testSpline_TwoSegmentsHighLight() throws Exception {
        final float brightness0 = 10.0f;
        final float brightness1 = 50.0f;
        final float brightness2 = 60.0f;
        setHighLightBrightnesses(brightness0, brightness1, brightness2);
        final float bias0 = 0.0f;
        final float bias1 = 0.25f;
        final float bias2 = 1.0f;
        setHighLightBiases(bias0, bias1, bias2);

        DisplayWhiteBalanceController controller =
                DisplayWhiteBalanceFactory.create(mHandler, mSensorManagerMock, mResourcesSpy);
        final float ambientColorTemperature = 6000.0f;
        setEstimatedColorTemperature(controller, ambientColorTemperature);
        controller.mBrightnessFilter = spy(new AmbientFilterStubber());

        for (float t = 0.0f; t <= 1.0f; t += 0.1f) {
            float luxOverride = mix(brightness0, brightness1, t);
            setEstimatedBrightnessAndUpdate(controller, luxOverride);
            float bias = mix(bias0, bias1, t);
            assertEquals(controller.mPendingAmbientColorTemperature,
                    mix(HIGH_LIGHT_AMBIENT_COLOR_TEMPERATURE, ambientColorTemperature, 1.0f - bias),
                    0.01);
        }

        for (float t = 0.0f; t <= 1.0f; t += 0.1f) {
            float luxOverride = mix(brightness1, brightness2, t);
            setEstimatedBrightnessAndUpdate(controller, luxOverride);
            float bias = mix(bias1, bias2, t);
            assertEquals(controller.mPendingAmbientColorTemperature,
                    mix(HIGH_LIGHT_AMBIENT_COLOR_TEMPERATURE, ambientColorTemperature, 1.0f - bias),
                    0.01);
        }

        setEstimatedBrightnessAndUpdate(controller, brightness2 + 1.0f);
        assertEquals(controller.mPendingAmbientColorTemperature,
                HIGH_LIGHT_AMBIENT_COLOR_TEMPERATURE, 0.001);

        setEstimatedBrightnessAndUpdate(controller, 0.0f);
        assertEquals(controller.mPendingAmbientColorTemperature, ambientColorTemperature, 0.001);
    }

    @Test
    public void testSpline_InvalidCombinations() throws Exception {
            setBrightnesses(100.0f, 200.0f);
            setBiases(0.0f, 1.0f);
            setHighLightBrightnesses(150.0f, 250.0f);
            setHighLightBiases(0.0f, 1.0f);

            DisplayWhiteBalanceController controller =
                    DisplayWhiteBalanceFactory.create(mHandler, mSensorManagerMock, mResourcesSpy);
            final float ambientColorTemperature = 8000.0f;
            setEstimatedColorTemperature(controller, ambientColorTemperature);
            controller.mBrightnessFilter = spy(new AmbientFilterStubber());

            for (float luxOverride = 0.1f; luxOverride <= 10000; luxOverride *= 10) {
                setEstimatedBrightnessAndUpdate(controller, luxOverride);
                assertEquals(controller.mPendingAmbientColorTemperature,
                        ambientColorTemperature, 0.001);
            }
    }

    @Test
    public void testLowLight_DefaultAmbient() throws Exception {
        final float lowerBrightness = 10.0f;
        final float upperBrightness = 50.0f;
        setBrightnesses(lowerBrightness, upperBrightness);
        setBiases(0.0f, 1.0f);

        DisplayWhiteBalanceController controller =
                DisplayWhiteBalanceFactory.create(mHandler, mSensorManagerMock, mResourcesSpy);
        final float ambientColorTemperature = -1.0f;
        setEstimatedColorTemperature(controller, ambientColorTemperature);
        controller.mBrightnessFilter = spy(new AmbientFilterStubber());

        for (float t = 0.0f; t <= 1.0f; t += 0.1f) {
            setEstimatedBrightnessAndUpdate(controller,
                    mix(lowerBrightness, upperBrightness, t));
            assertEquals(controller.mPendingAmbientColorTemperature, ambientColorTemperature,
                        0.001);
        }

        setEstimatedBrightnessAndUpdate(controller, 0.0f);
        assertEquals(controller.mPendingAmbientColorTemperature, ambientColorTemperature, 0.001);

        setEstimatedBrightnessAndUpdate(controller, upperBrightness + 1.0f);
        assertEquals(controller.mPendingAmbientColorTemperature, ambientColorTemperature, 0.001);
    }

    void mockThrottler() {
        when(mResourcesSpy.getInteger(
                R.integer.config_displayWhiteBalanceDecreaseDebounce)).thenReturn(0);
        when(mResourcesSpy.getInteger(
                R.integer.config_displayWhiteBalanceIncreaseDebounce)).thenReturn(0);
        TypedArray base = mResourcesSpy.obtainTypedArray(
                R.array.config_displayWhiteBalanceBaseThresholds);
        TypedArray inc = mResourcesSpy.obtainTypedArray(
                R.array.config_displayWhiteBalanceIncreaseThresholds);
        TypedArray dec = mResourcesSpy.obtainTypedArray(
                R.array.config_displayWhiteBalanceDecreaseThresholds);
        base = spy(base);
        inc = spy(inc);
        dec = spy(dec);
        when(mResourcesSpy.obtainTypedArray(
                R.array.config_displayWhiteBalanceBaseThresholds)).thenReturn(base);
        when(mResourcesSpy.obtainTypedArray(
                R.array.config_displayWhiteBalanceIncreaseThresholds)).thenReturn(inc);
        when(mResourcesSpy.obtainTypedArray(
                R.array.config_displayWhiteBalanceDecreaseThresholds)).thenReturn(dec);
        setFloatArrayResource(base, new float[]{0.0f});
        setFloatArrayResource(inc, new float[]{0.0f});
        setFloatArrayResource(dec, new float[]{0.0f});
    }

    private void mockResourcesFloat(int id, float floatValue) {
        doAnswer(new Answer<Void>() {
            public Void answer(InvocationOnMock invocation) {
                TypedValue value = (TypedValue)invocation.getArgument(1);
                value.type = TypedValue.TYPE_FLOAT;
                value.data = Float.floatToIntBits(floatValue);
                return null;
            }
        }).when(mResourcesSpy).getValue(
                eq(id),
                any(TypedValue.class), eq(true));
    }

    private void setEstimatedColorTemperature(DisplayWhiteBalanceController controller,
                                              float ambientColorTemperature) {
        AmbientFilter colorTemperatureFilter = spy(new AmbientFilterStubber());
        controller.mColorTemperatureFilter = colorTemperatureFilter;
        when(colorTemperatureFilter.getEstimate(anyLong())).thenReturn(ambientColorTemperature);
    }

    private void setEstimatedBrightnessAndUpdate(DisplayWhiteBalanceController controller,
                                                 float brightness) {
        when(controller.mBrightnessFilter.getEstimate(anyLong())).thenReturn(brightness);
        controller.updateAmbientColorTemperature();
    }

    private void setBrightnesses(float... vals) {
        setFloatArrayResource(mBrightnesses, vals);
    }

    private void setBiases(float... vals) {
        setFloatArrayResource(mBiases, vals);
    }

    private void setHighLightBrightnesses(float... vals) {
        setFloatArrayResource(mHighLightBrightnesses, vals);
    }

    private void setHighLightBiases(float... vals) {
        setFloatArrayResource(mHighLightBiases, vals);
    }

    private void setFloatArrayResource(TypedArray array, float[] vals) {
        when(array.length()).thenReturn(vals.length);
        for (int i = 0; i < vals.length; i++) {
            when(array.getFloat(i, Float.NaN)).thenReturn(vals[i]);
        }
    }

    private void setSensorType(Sensor sensor, int type, String strType) throws Exception {
        Method setter = Sensor.class.getDeclaredMethod("setType", Integer.TYPE);
        setter.setAccessible(true);
        setter.invoke(sensor, type);
        if (strType != null) {
            Field f = sensor.getClass().getDeclaredField("mStringType");
            f.setAccessible(true);
            f.set(sensor, strType);
        }
    }

    private Sensor createSensor(int type, String strType) throws Exception {
        Constructor<Sensor> constr = Sensor.class.getDeclaredConstructor();
        constr.setAccessible(true);
        Sensor sensor = constr.newInstance();
        setSensorType(sensor, type, strType);
        return sensor;
    }

    private TypedArray createTypedArray() throws Exception {
        TypedArray mockArray = mock(TypedArray.class);
        return mockArray;
    }

    private static float mix(float a, float b, float t) {
        return (1.0f - t) * a + t * b;
    }
}
