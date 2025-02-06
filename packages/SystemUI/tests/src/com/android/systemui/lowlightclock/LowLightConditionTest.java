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

package com.android.systemui.lowlightclock;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;

import kotlinx.coroutines.CoroutineScope;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class LowLightConditionTest extends SysuiTestCase {
    @Mock
    private AmbientLightModeMonitor mAmbientLightModeMonitor;
    @Mock
    private UiEventLogger mUiEventLogger;
    @Mock
    CoroutineScope mScope;
    private LowLightCondition mCondition;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mCondition = new LowLightCondition(mScope, mAmbientLightModeMonitor, mUiEventLogger);
        mCondition.start();
    }

    @Test
    public void testLowLightFalse() {
        changeLowLightMode(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT);
        assertThat(mCondition.isConditionMet()).isFalse();
    }

    @Test
    public void testLowLightTrue() {
        changeLowLightMode(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK);
        assertThat(mCondition.isConditionMet()).isTrue();
    }

    @Test
    public void testUndecidedLowLightStateIgnored() {
        changeLowLightMode(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK);
        assertThat(mCondition.isConditionMet()).isTrue();
        changeLowLightMode(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_UNDECIDED);
        assertThat(mCondition.isConditionMet()).isTrue();
    }

    @Test
    public void testLowLightChange() {
        changeLowLightMode(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT);
        assertThat(mCondition.isConditionMet()).isFalse();
        changeLowLightMode(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK);
        assertThat(mCondition.isConditionMet()).isTrue();
    }

    @Test
    public void testResetIsConditionMetUponStop() {
        changeLowLightMode(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK);
        assertThat(mCondition.isConditionMet()).isTrue();

        mCondition.stop();
        assertThat(mCondition.isConditionMet()).isFalse();
    }

    @Test
    public void testLoggingAmbientLightNotLowToLow() {
        changeLowLightMode(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK);
        // Only logged once.
        verify(mUiEventLogger, times(1)).log(any());
        // Logged with the correct state.
        verify(mUiEventLogger).log(LowLightDockEvent.AMBIENT_LIGHT_TO_DARK);
    }

    @Test
    public void testLoggingAmbientLightLowToLow() {
        changeLowLightMode(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK);
        reset(mUiEventLogger);

        changeLowLightMode(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK);
        // Doesn't log.
        verify(mUiEventLogger, never()).log(any());
    }

    @Test
    public void testLoggingAmbientLightNotLowToNotLow() {
        changeLowLightMode(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT);
        // Doesn't log.
        verify(mUiEventLogger, never()).log(any());
    }

    @Test
    public void testLoggingAmbientLightLowToNotLow() {
        changeLowLightMode(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK);
        reset(mUiEventLogger);

        changeLowLightMode(AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT);
        // Only logged once.
        verify(mUiEventLogger).log(any());
        // Logged with the correct state.
        verify(mUiEventLogger).log(LowLightDockEvent.AMBIENT_LIGHT_TO_LIGHT);
    }

    private void changeLowLightMode(int mode) {
        ArgumentCaptor<AmbientLightModeMonitor.Callback> ambientLightCallbackCaptor =
                ArgumentCaptor.forClass(AmbientLightModeMonitor.Callback.class);
        verify(mAmbientLightModeMonitor).start(ambientLightCallbackCaptor.capture());
        ambientLightCallbackCaptor.getValue().onChange(mode);
    }
}
