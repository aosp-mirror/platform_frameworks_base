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

package androidx.window.extensions.area;

import static android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_REAR_DISPLAY;
import static android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_REAR_DISPLAY_OUTER_DEFAULT;
import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE_IDENTIFIER;

import static org.junit.Assert.assertEquals;

import android.hardware.devicestate.DeviceState;
import android.platform.test.annotations.Presubmit;
import android.util.DisplayMetrics;
import android.view.Surface;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class WindowAreaComponentImplTests {

    private static final DeviceState REAR_DISPLAY_STATE_V1 = new DeviceState(
            new DeviceState.Configuration.Builder(1, "STATE_0")
                    .setSystemProperties(
                            Set.of(PROPERTY_FEATURE_REAR_DISPLAY))
                    .build());
    private static final DeviceState REAR_DISPLAY_STATE_V2 = new DeviceState(
            new DeviceState.Configuration.Builder(2, "STATE_0")
                    .setSystemProperties(
                            Set.of(PROPERTY_FEATURE_REAR_DISPLAY,
                                    PROPERTY_FEATURE_REAR_DISPLAY_OUTER_DEFAULT))
                    .build());
    // The PROPERTY_FEATURE_REAR_DISPLAY_OUTER_DEFAULT state must be present together with the
    // PROPERTY_FEATURE_REAR_DISPLAY state in order to be a valid state.
    private static final DeviceState INVALID_REAR_DISPLAY_STATE = new DeviceState(
            new DeviceState.Configuration.Builder(2, "STATE_0")
                    .setSystemProperties(
                            Set.of(PROPERTY_FEATURE_REAR_DISPLAY_OUTER_DEFAULT))
                    .build());

    private final DisplayMetrics mTestDisplayMetrics = new DisplayMetrics();

    @Before
    public void setup() {
        mTestDisplayMetrics.widthPixels = 1;
        mTestDisplayMetrics.heightPixels = 2;
        mTestDisplayMetrics.noncompatWidthPixels = 3;
        mTestDisplayMetrics.noncompatHeightPixels = 4;
    }

    /**
     * Cases where the rear display metrics does not need to be transformed.
     */
    @Test
    public void testRotateRearDisplayMetrics_noTransformNeeded() {
        final DisplayMetrics originalMetrics = new DisplayMetrics();
        originalMetrics.setTo(mTestDisplayMetrics);

        WindowAreaComponentImpl.rotateRearDisplayMetricsIfNeeded(
                Surface.ROTATION_0, Surface.ROTATION_0, mTestDisplayMetrics);
        assertEquals(originalMetrics, mTestDisplayMetrics);

        WindowAreaComponentImpl.rotateRearDisplayMetricsIfNeeded(
                Surface.ROTATION_180, Surface.ROTATION_180, mTestDisplayMetrics);
        assertEquals(originalMetrics, mTestDisplayMetrics);

        WindowAreaComponentImpl.rotateRearDisplayMetricsIfNeeded(
                Surface.ROTATION_0, Surface.ROTATION_180, mTestDisplayMetrics);
        assertEquals(originalMetrics, mTestDisplayMetrics);

        WindowAreaComponentImpl.rotateRearDisplayMetricsIfNeeded(
                Surface.ROTATION_180, Surface.ROTATION_0, mTestDisplayMetrics);
        assertEquals(originalMetrics, mTestDisplayMetrics);
    }

    /**
     * Cases where the rear display metrics need to be transformed.
     */
    @Test
    public void testRotateRearDisplayMetrics_transformNeeded() {
        DisplayMetrics originalMetrics = new DisplayMetrics();
        originalMetrics.setTo(mTestDisplayMetrics);

        DisplayMetrics expectedMetrics = new DisplayMetrics();
        expectedMetrics.setTo(mTestDisplayMetrics);
        expectedMetrics.widthPixels = mTestDisplayMetrics.heightPixels;
        expectedMetrics.heightPixels = mTestDisplayMetrics.widthPixels;
        expectedMetrics.noncompatWidthPixels = mTestDisplayMetrics.noncompatHeightPixels;
        expectedMetrics.noncompatHeightPixels = mTestDisplayMetrics.noncompatWidthPixels;

        WindowAreaComponentImpl.rotateRearDisplayMetricsIfNeeded(
                Surface.ROTATION_90, Surface.ROTATION_0, mTestDisplayMetrics);
        assertEquals(expectedMetrics, mTestDisplayMetrics);

        mTestDisplayMetrics.setTo(originalMetrics);
        WindowAreaComponentImpl.rotateRearDisplayMetricsIfNeeded(
                Surface.ROTATION_270, Surface.ROTATION_0, mTestDisplayMetrics);
        assertEquals(expectedMetrics, mTestDisplayMetrics);
    }

    @Test
    public void testRdmV1Identifier() {
        final List<DeviceState> supportedStates = new ArrayList<>();
        supportedStates.add(REAR_DISPLAY_STATE_V2);
        assertEquals(INVALID_DEVICE_STATE_IDENTIFIER,
                WindowAreaComponentImpl.getRdmV1Identifier(supportedStates));

        supportedStates.add(REAR_DISPLAY_STATE_V1);
        assertEquals(REAR_DISPLAY_STATE_V1.getIdentifier(),
                WindowAreaComponentImpl.getRdmV1Identifier(supportedStates));
    }

    @Test
    public void testRdmV2Identifier_whenStateIsImproperlyConfigured() {
        final List<DeviceState> supportedStates = new ArrayList<>();
        supportedStates.add(INVALID_REAR_DISPLAY_STATE);
        assertEquals(INVALID_DEVICE_STATE_IDENTIFIER,
                WindowAreaComponentImpl.getRdmV2Identifier(supportedStates));
    }

    @Test
    public void testRdmV2Identifier_whenStateIsProperlyConfigured() {
        final List<DeviceState> supportedStates = new ArrayList<>();

        supportedStates.add(REAR_DISPLAY_STATE_V1);
        assertEquals(INVALID_DEVICE_STATE_IDENTIFIER,
                WindowAreaComponentImpl.getRdmV2Identifier(supportedStates));

        supportedStates.add(REAR_DISPLAY_STATE_V2);
        assertEquals(REAR_DISPLAY_STATE_V2.getIdentifier(),
                WindowAreaComponentImpl.getRdmV2Identifier(supportedStates));
    }
}
