/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.power;

import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_DIM;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_OFF;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_VR;
import static android.provider.DeviceConfig.NAMESPACE_ATTENTION_MANAGER_SERVICE;
import static android.view.Display.DEFAULT_DISPLAY_GROUP;

import static com.android.server.power.ScreenUndimDetector.DEFAULT_MAX_DURATION_BETWEEN_UNDIMS_MILLIS;
import static com.android.server.power.ScreenUndimDetector.KEY_KEEP_SCREEN_ON_ENABLED;
import static com.android.server.power.ScreenUndimDetector.KEY_MAX_DURATION_BETWEEN_UNDIMS_MILLIS;
import static com.android.server.power.ScreenUndimDetector.KEY_UNDIMS_REQUIRED;

import static com.google.common.truth.Truth.assertThat;

import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.testing.TestableContext;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.modules.utils.testing.TestableDeviceConfig;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;

/**
 * Tests for {@link com.android.server.power.ScreenUndimDetector}
 */
@RunWith(JUnit4.class)
public class ScreenUndimDetectorTest {
    private static final List<Integer> ALL_POLICIES =
            Arrays.asList(POLICY_OFF,
                    POLICY_DOZE,
                    POLICY_DIM,
                    POLICY_BRIGHT,
                    POLICY_VR);
    private static final int OTHER_DISPLAY_GROUP = DEFAULT_DISPLAY_GROUP + 1;

    @ClassRule
    public static final TestableContext sContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);
    @Rule
    public TestableDeviceConfig.TestableDeviceConfigRule
            mDeviceConfigRule = new TestableDeviceConfig.TestableDeviceConfigRule();

    private ScreenUndimDetector mScreenUndimDetector;

    private final TestClock mClock = new TestClock();

    private static class TestClock extends ScreenUndimDetector.InternalClock {
        long mCurrentTime = 0;
        @Override
        public long getCurrentTime() {
            return mCurrentTime;
        }

        public void advanceTime(long millisAdvanced) {
            mCurrentTime += millisAdvanced;
        }
    }

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_UNDIMS_REQUIRED,
                Integer.toString(1), false /*makeDefault*/);
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_MAX_DURATION_BETWEEN_UNDIMS_MILLIS,
                Long.toString(DEFAULT_MAX_DURATION_BETWEEN_UNDIMS_MILLIS),
                false /*makeDefault*/);

        mScreenUndimDetector = new ScreenUndimDetector(mClock);
        mScreenUndimDetector.systemReady(sContext);
    }

    @Test
    public void recordScreenPolicy_disabledByFlag_noop() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_KEEP_SCREEN_ON_ENABLED, Boolean.FALSE.toString(), false /*makeDefault*/);

        setup();
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);

        assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isFalse();
    }

    @Test
    public void recordScreenPolicy_samePolicy_noop() {
        for (int policy : ALL_POLICIES) {
            setup();
            mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, policy);
            mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, policy);

            assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isFalse();
        }
    }

    @Test
    public void recordScreenPolicy_dimToBright_extends() {
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);

        assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isTrue();
    }

    @Test
    public void recordScreenPolicy_dimToBright_ignoresOtherDisplayGroup() {
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);

        mScreenUndimDetector.recordScreenPolicy(OTHER_DISPLAY_GROUP, POLICY_BRIGHT);
        assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isFalse();

        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);
        assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isTrue();
    }

    @Test
    public void recordScreenPolicy_otherTransitions_doesNotExtend() {
        for (int from : ALL_POLICIES) {
            for (int to : ALL_POLICIES) {
                if (from == POLICY_DIM && to == POLICY_BRIGHT) {
                    continue;
                }
                setup();
                mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, from);
                mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, to);

                assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isFalse();
                assertThat(mScreenUndimDetector.mUndimCounter).isEqualTo(0);
            }
        }
    }

    @Test
    public void recordScreenPolicy_dimToBright_twoUndimsNeeded_extends() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_UNDIMS_REQUIRED,
                Integer.toString(2), false /*makeDefault*/);
        mScreenUndimDetector.readValuesFromDeviceConfig();

        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);

        assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isFalse();

        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);

        assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isTrue();
    }

    @Test
    public void recordScreenPolicy_dimToBright_twoUndimsNeeded_otherDisplayDoesNotExtend() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_UNDIMS_REQUIRED,
                Integer.toString(2), false /*makeDefault*/);
        mScreenUndimDetector.readValuesFromDeviceConfig();

        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);

        assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isFalse();

        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mScreenUndimDetector.recordScreenPolicy(OTHER_DISPLAY_GROUP, POLICY_BRIGHT);

        assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isFalse();

        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);
        assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isTrue();
    }

    @Test
    public void recordScreenPolicy_dimBrightDimOff_resetsCounter_doesNotExtend() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_UNDIMS_REQUIRED,
                Integer.toString(2), false /*makeDefault*/);
        mScreenUndimDetector.readValuesFromDeviceConfig();

        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_OFF);

        assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isFalse();
        assertThat(mScreenUndimDetector.mUndimCounter).isEqualTo(0);
    }

    @Test
    public void recordScreenPolicy_undimToOff_resetsCounter() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_UNDIMS_REQUIRED,
                Integer.toString(2), false /*makeDefault*/);
        mScreenUndimDetector.readValuesFromDeviceConfig();

        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_OFF);

        assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isFalse();
        assertThat(mScreenUndimDetector.mUndimCounter).isEqualTo(0);
    }

    @Test
    public void recordScreenPolicy_undimToOff_otherDisplayDoesNotResetCounter() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_UNDIMS_REQUIRED,
                Integer.toString(2), false /*makeDefault*/);
        mScreenUndimDetector.readValuesFromDeviceConfig();

        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);

        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_OFF);

        assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isFalse();
        assertThat(mScreenUndimDetector.mUndimCounter).isEqualTo(0);
    }

    @Test
    public void recordScreenPolicy_undimOffUndim_doesNotExtend() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_UNDIMS_REQUIRED,
                Integer.toString(2), false /*makeDefault*/);
        mScreenUndimDetector.readValuesFromDeviceConfig();

        // undim
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);
        // off
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_OFF);
        // second undim
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);

        assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isFalse();
        assertThat(mScreenUndimDetector.mUndimCounter).isEqualTo(1);
    }

    @Test
    public void recordScreenPolicy_dimToBright_tooFarApart_doesNotExtend() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_UNDIMS_REQUIRED,
                Integer.toString(2), false /*makeDefault*/);
        mScreenUndimDetector.readValuesFromDeviceConfig();

        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);

        mClock.advanceTime(DEFAULT_MAX_DURATION_BETWEEN_UNDIMS_MILLIS + 5);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);
        mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);

        assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isFalse();
        assertThat(mScreenUndimDetector.mUndimCounter).isEqualTo(1);
    }

    @Test
    public void recordScreenPolicy_dimToNonBright_resets() {
        for (int to : Arrays.asList(POLICY_OFF, POLICY_DOZE, POLICY_VR)) {
            setup();
            mScreenUndimDetector.mUndimCounter = 1;
            mScreenUndimDetector.mUndimCounterStartedMillis = 123;
            mScreenUndimDetector.mWakeLock.acquire();

            mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_DIM);
            mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, to);

            assertThat(mScreenUndimDetector.mUndimCounter).isEqualTo(0);
            assertThat(mScreenUndimDetector.mUndimCounterStartedMillis).isEqualTo(0);
            assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isFalse();
        }

    }

    @Test
    public void recordScreenPolicy_brightToNonDim_resets() {
        for (int to : Arrays.asList(POLICY_OFF, POLICY_DOZE, POLICY_VR)) {
            setup();
            mScreenUndimDetector.mUndimCounter = 1;
            mScreenUndimDetector.mUndimCounterStartedMillis = 123;
            mScreenUndimDetector.mWakeLock.acquire();

            mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, POLICY_BRIGHT);
            mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, to);

            assertThat(mScreenUndimDetector.mUndimCounter).isEqualTo(0);
            assertThat(mScreenUndimDetector.mUndimCounterStartedMillis).isEqualTo(0);
            assertThat(mScreenUndimDetector.mWakeLock.isHeld()).isFalse();
        }
    }

    @Test
    public void recordScreenPolicy_otherTransitions_doesNotReset() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_UNDIMS_REQUIRED,
                Integer.toString(3),
                false /*makeDefault*/);
        mScreenUndimDetector.readValuesFromDeviceConfig();

        for (int from : ALL_POLICIES) {
            for (int to : ALL_POLICIES) {
                if (from == POLICY_DIM && to != POLICY_BRIGHT) {
                    continue;
                }
                if (from == POLICY_BRIGHT && to != POLICY_DIM) {
                    continue;
                }
                mScreenUndimDetector.mCurrentScreenPolicy = POLICY_OFF;
                mScreenUndimDetector.mUndimCounter = 1;
                mScreenUndimDetector.mUndimCounterStartedMillis =
                        SystemClock.currentThreadTimeMillis();

                mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, from);
                mScreenUndimDetector.recordScreenPolicy(DEFAULT_DISPLAY_GROUP, to);

                assertThat(mScreenUndimDetector.mUndimCounter).isNotEqualTo(0);
                assertThat(mScreenUndimDetector.mUndimCounterStartedMillis).isNotEqualTo(0);
            }
        }
    }
}
