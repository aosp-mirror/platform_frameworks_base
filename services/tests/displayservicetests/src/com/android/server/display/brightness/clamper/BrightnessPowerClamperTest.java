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

import static com.android.server.display.brightness.clamper.BrightnessPowerClamper.PowerChangeListener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Temperature;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingConfigData;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingData;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel;
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.testutils.FakeDeviceConfigInterface;
import com.android.server.testutils.TestHandler;

import junitparams.JUnitParamsRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@RunWith(JUnitParamsRunner.class)
public class BrightnessPowerClamperTest {
    private static final String TAG = "BrightnessPowerClamperTest";
    private static final float FLOAT_TOLERANCE = 0.001f;

    private static final String DISPLAY_ID = "displayId";
    @Mock
    private BrightnessClamperController.ClamperChangeListener mMockClamperChangeListener;
    private TestPmicMonitor mPmicMonitor;
    private final FakeDeviceConfigInterface mFakeDeviceConfigInterface =
            new FakeDeviceConfigInterface();
    private final TestHandler mTestHandler = new TestHandler(null);
    private final TestInjector mTestInjector = new TestInjector();
    private BrightnessPowerClamper mClamper;
    private final float mCurrentBrightness = 0.6f;
    private PowerChangeListener mPowerChangeListener;
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mClamper = new BrightnessPowerClamper(mTestInjector, mTestHandler,
                mMockClamperChangeListener, new TestPowerData(), mCurrentBrightness);
        mPowerChangeListener = mClamper.getPowerChangeListener();
        mPmicMonitor = mTestInjector.getPmicMonitor(mPowerChangeListener, null, 5, 10);
        mPmicMonitor.setPowerChangeListener(mPowerChangeListener);
        mTestHandler.flush();
    }

    @Test
    public void testTypeIsPower() {
        assertEquals(BrightnessClamper.Type.POWER, mClamper.getType());
    }

    @Test
    public void testNoThrottlingData() {
        assertFalse(mClamper.isActive());
        assertEquals(PowerManager.BRIGHTNESS_MAX, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);
    }

    @Test
    public void testPowerThrottlingWithThermalLevelLight() throws RemoteException {
        mPmicMonitor.setThermalStatus(Temperature.THROTTLING_LIGHT);
        mTestHandler.flush();
        assertFalse(mClamper.isActive());
        assertEquals(PowerManager.BRIGHTNESS_MAX, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);

        // update a new device config for power-throttling.
        mClamper.onDisplayChanged(new TestPowerData(
                List.of(new ThrottlingLevel(PowerManager.THERMAL_STATUS_LIGHT, 100f))));

        mPmicMonitor.setAvgPowerConsumed(200f);
        float expectedBrightness = 0.5f;
        expectedBrightness = expectedBrightness * mCurrentBrightness;

        mTestHandler.flush();
        // Assume current brightness as max, as there is no throttling.
        assertEquals(expectedBrightness, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);
    }

    @Test
    public void testPowerThrottlingWithThermalLevelSevere() throws RemoteException {
        mPmicMonitor.setThermalStatus(Temperature.THROTTLING_SEVERE);
        mTestHandler.flush();
        assertEquals(PowerManager.BRIGHTNESS_MAX, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);

        // update a new device config for power-throttling.
        mClamper.onDisplayChanged(new TestPowerData(
                List.of(new ThrottlingLevel(PowerManager.THERMAL_STATUS_SEVERE, 100f))));

        mPmicMonitor.setAvgPowerConsumed(200f);
        float expectedBrightness = 0.5f;
        expectedBrightness = expectedBrightness * mCurrentBrightness;
        mTestHandler.flush();
        // Assume current brightness as max, as there is no throttling.
        assertEquals(expectedBrightness, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);
    }

    @Test
    public void testPowerThrottlingRemoveBrightnessCap() throws RemoteException {
        mPmicMonitor.setThermalStatus(Temperature.THROTTLING_LIGHT);
        mTestHandler.flush();
        assertFalse(mClamper.isActive());
        assertEquals(PowerManager.BRIGHTNESS_MAX, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);

        // update a new device config for power-throttling.
        mClamper.onDisplayChanged(new TestPowerData(
                List.of(new ThrottlingLevel(PowerManager.THERMAL_STATUS_LIGHT, 100f))));

        mPmicMonitor.setAvgPowerConsumed(200f);
        float expectedBrightness = 0.5f;
        expectedBrightness = expectedBrightness * mCurrentBrightness;
        mTestHandler.flush();

        assertEquals(expectedBrightness, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);
        mPmicMonitor.setThermalStatus(Temperature.THROTTLING_NONE);

        mPmicMonitor.setAvgPowerConsumed(100f);
        // No cap applied for Temperature.THROTTLING_NONE
        expectedBrightness = PowerManager.BRIGHTNESS_MAX;
        mTestHandler.flush();

        // clamper should not be active anymore.
        assertFalse(mClamper.isActive());
        // Assume current brightness as max, as there is no throttling.
        assertEquals(expectedBrightness, mClamper.getBrightnessCap(), FLOAT_TOLERANCE);
    }


    private static class TestPmicMonitor extends PmicMonitor {
        private Temperature mCurrentTemperature;
        private PowerChangeListener mListener;
        TestPmicMonitor(PowerChangeListener listener,
                        IThermalService thermalService,
                        int pollingTimeMax, int pollingTimeMin) {
            super(listener, thermalService, pollingTimeMax, pollingTimeMin);
        }
        public void setAvgPowerConsumed(float power) {
            int status = mCurrentTemperature.getStatus();
            mListener.onChanged(power, status);
        }
        public void setThermalStatus(@Temperature.ThrottlingStatus int status) {
            mCurrentTemperature = new Temperature(100, Temperature.TYPE_SKIN, "test_temp", status);
        }
        public void setPowerChangeListener(PowerChangeListener listener) {
            mListener = listener;
        }
    }

    private class TestInjector extends BrightnessPowerClamper.Injector {
        @Override
        TestPmicMonitor getPmicMonitor(PowerChangeListener listener,
                                       IThermalService thermalService,
                                       int minPollingTimeMillis, int maxPollingTimeMillis) {
            mPmicMonitor = new TestPmicMonitor(listener, thermalService, maxPollingTimeMillis,
                    minPollingTimeMillis);
            return mPmicMonitor;
        }

        @Override
        DeviceConfigParameterProvider getDeviceConfigParameterProvider() {
            return new DeviceConfigParameterProvider(mFakeDeviceConfigInterface);
        }
    }

    private static class TestPowerData implements BrightnessPowerClamper.PowerData {

        private final String mUniqueDisplayId;
        private final String mDataId;
        private final PowerThrottlingData mData;
        private final PowerThrottlingConfigData mConfigData;

        private TestPowerData() {
            this(DISPLAY_ID, DisplayDeviceConfig.DEFAULT_ID, null);
        }

        private TestPowerData(List<ThrottlingLevel> data) {
            this(DISPLAY_ID, DisplayDeviceConfig.DEFAULT_ID, data);
        }

        private TestPowerData(String uniqueDisplayId, String dataId, List<ThrottlingLevel> data) {
            mUniqueDisplayId = uniqueDisplayId;
            mDataId = dataId;
            mData = PowerThrottlingData.create(data);
            mConfigData = new PowerThrottlingConfigData(0.1f, 10, 20, 10);
        }

        @NonNull
        @Override
        public String getUniqueDisplayId() {
            return mUniqueDisplayId;
        }

        @NonNull
        @Override
        public String getPowerThrottlingDataId() {
            return mDataId;
        }

        @Nullable
        @Override
        public PowerThrottlingData getPowerThrottlingData() {
            return mData;
        }

        @Nullable
        @Override
        public PowerThrottlingConfigData getPowerThrottlingConfigData() {
            return mConfigData;
        }
    }
}
