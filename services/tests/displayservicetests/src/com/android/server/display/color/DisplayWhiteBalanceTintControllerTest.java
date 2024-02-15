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

package com.android.server.display.color;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManagerInternal;
import android.os.Binder;
import android.os.IBinder;
import android.view.SurfaceControl;

import androidx.test.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.R;
import com.android.server.display.feature.DisplayManagerFlags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.Arrays;

public class DisplayWhiteBalanceTintControllerTest {
    @Mock
    private Context mMockedContext;
    @Mock
    private Resources mMockedResources;
    @Mock
    private DisplayManagerInternal mDisplayManagerInternal;
    @Mock
    private DisplayManagerFlags mDisplayManagerFlagsMock;

    private MockitoSession mSession;
    private Resources mResources;
    IBinder mDisplayToken;
    DisplayWhiteBalanceTintController mDisplayWhiteBalanceTintController;

    @Before
    public void setUp() {
        DisplayManagerInternal displayManagerInternal = mock(DisplayManagerInternal.class);

        mSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .mockStatic(SurfaceControl.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        mDisplayWhiteBalanceTintController =
                new DisplayWhiteBalanceTintController(displayManagerInternal,
                        mDisplayManagerFlagsMock);
        mDisplayWhiteBalanceTintController.setUp(InstrumentationRegistry.getContext(), true);
        mDisplayWhiteBalanceTintController.setActivated(true);


        mResources = InstrumentationRegistry.getContext().getResources();
        // These Resources are common to all tests.
        doReturn(4000)
                .when(mMockedResources)
                .getInteger(R.integer.config_displayWhiteBalanceColorTemperatureMin);
        doReturn(8000)
                .when(mMockedResources)
                .getInteger(R.integer.config_displayWhiteBalanceColorTemperatureMax);
        doReturn(6500)
                .when(mMockedResources)
                .getInteger(R.integer.config_displayWhiteBalanceColorTemperatureDefault);
        doReturn(new String[] {"0.950456", "1.000000", "1.089058"})
                .when(mMockedResources)
                .getStringArray(R.array.config_displayWhiteBalanceDisplayNominalWhite);
        doReturn(6500)
                .when(mMockedResources)
                .getInteger(R.integer.config_displayWhiteBalanceDisplayNominalWhiteCct);
        doReturn(new int[] {0})
                .when(mMockedResources)
                .getIntArray(R.array.config_displayWhiteBalanceDisplaySteps);
        doReturn(new int[] {20})
                .when(mMockedResources)
                .getIntArray(R.array.config_displayWhiteBalanceDisplayRangeMinimums);

        doReturn(mMockedResources).when(mMockedContext).getResources();

        mDisplayToken = new Binder();
    }

    @After
    public void tearDown() throws Exception {
        if (mSession != null) {
            mSession.finishMocking();
        }
    }

    @Test
    public void displayWhiteBalance_setTemperatureOverMax() {
        final int max = mDisplayWhiteBalanceTintController.mTemperatureMax;
        mDisplayWhiteBalanceTintController.setMatrix(max + 1);
        assertWithMessage("Unexpected temperature set")
                .that(mDisplayWhiteBalanceTintController.mCurrentColorTemperature)
                .isEqualTo(max);
    }

    @Test
    public void displayWhiteBalance_setTemperatureBelowMin() {
        final int min = mDisplayWhiteBalanceTintController.mTemperatureMin;
        mDisplayWhiteBalanceTintController.setMatrix(min - 1);
        assertWithMessage("Unexpected temperature set")
                .that(mDisplayWhiteBalanceTintController.mCurrentColorTemperature)
                .isEqualTo(min);
    }

    @Test
    public void displayWhiteBalance_setValidTemperature() {
        final int colorTemperature = (mDisplayWhiteBalanceTintController.mTemperatureMin
                + mDisplayWhiteBalanceTintController.mTemperatureMax) / 2;
        mDisplayWhiteBalanceTintController.setMatrix(colorTemperature);

        assertWithMessage("Unexpected temperature set")
                .that(mDisplayWhiteBalanceTintController.mCurrentColorTemperature)
                .isEqualTo(colorTemperature);
    }

    @Test
    public void displayWhiteBalance_setMatrixValidDwbCalculation() {
        float[] currentMatrix = mDisplayWhiteBalanceTintController.getMatrix();
        float[] oldMatrix = Arrays.copyOf(currentMatrix, currentMatrix.length);

        mDisplayWhiteBalanceTintController
                .setMatrix(mDisplayWhiteBalanceTintController.mCurrentColorTemperature + 1);
        assertWithMessage("DWB matrix did not change when setting a new temperature")
                .that(Arrays.equals(oldMatrix, currentMatrix))
                .isFalse();
    }

    @Test
    public void displayWhiteBalance_setMatrixInvalidDwbCalculation() {
        Arrays.fill(mDisplayWhiteBalanceTintController.mDisplayNominalWhiteXYZ, 0);
        mDisplayWhiteBalanceTintController
            .setMatrix(mDisplayWhiteBalanceTintController.mCurrentColorTemperature + 1);
        assertWithMessage("DWB matrix not set to identity after an invalid DWB calculation")
                .that(Arrays.equals(mDisplayWhiteBalanceTintController.getMatrix(),
                    new float[] {
                        1, 0, 0, 0,
                        0, 1, 0, 0,
                        0, 0, 1, 0,
                        0, 0, 0, 1
                    })
                ).isTrue();
    }


    /**
     * Setup should succeed when SurfaceControl setup results in a valid color transform.
     */
    @Test
    public void displayWhiteBalance_setupWithSurfaceControl() {
        // Make SurfaceControl return sRGB primaries
        SurfaceControl.DisplayPrimaries displayPrimaries = new SurfaceControl.DisplayPrimaries();
        displayPrimaries.red = new SurfaceControl.CieXyz();
        displayPrimaries.red.X = 0.412315f;
        displayPrimaries.red.Y = 0.212600f;
        displayPrimaries.red.Z = 0.019327f;
        displayPrimaries.green = new SurfaceControl.CieXyz();
        displayPrimaries.green.X = 0.357600f;
        displayPrimaries.green.Y = 0.715200f;
        displayPrimaries.green.Z = 0.119200f;
        displayPrimaries.blue = new SurfaceControl.CieXyz();
        displayPrimaries.blue.X = 0.180500f;
        displayPrimaries.blue.Y = 0.072200f;
        displayPrimaries.blue.Z = 0.950633f;
        displayPrimaries.white = new SurfaceControl.CieXyz();
        displayPrimaries.white.X = 0.950456f;
        displayPrimaries.white.Y = 1.000000f;
        displayPrimaries.white.Z = 1.089058f;
        when(mDisplayManagerInternal.getDisplayNativePrimaries(DEFAULT_DISPLAY))
                .thenReturn(displayPrimaries);

        setUpTintController();
        assertWithMessage("Setup with valid SurfaceControl failed")
                .that(mDisplayWhiteBalanceTintController.mSetUp)
                .isTrue();
    }

    /**
     * Setup should fail when SurfaceControl setup results in an invalid color transform.
     */
    @Test
    public void displayWhiteBalance_setupWithInvalidSurfaceControlData() {
        // Make SurfaceControl return invalid display primaries
        SurfaceControl.DisplayPrimaries displayPrimaries = new SurfaceControl.DisplayPrimaries();
        displayPrimaries.red = new SurfaceControl.CieXyz();
        displayPrimaries.green = new SurfaceControl.CieXyz();
        displayPrimaries.blue = new SurfaceControl.CieXyz();
        displayPrimaries.white = new SurfaceControl.CieXyz();
        when(mDisplayManagerInternal.getDisplayNativePrimaries(DEFAULT_DISPLAY))
                .thenReturn(displayPrimaries);

        setUpTintController();
        assertWithMessage("Setup with invalid SurfaceControl succeeded")
                .that(mDisplayWhiteBalanceTintController.mSetUp)
                .isFalse();
    }

    /**
     * Setup should succeed when SurfaceControl setup fails and Resources result in a valid color
     * transform.
     */
    @Test
    public void displayWhiteBalance_setupWithResources() {
        // Use default (valid) Resources
        doReturn(mResources.getStringArray(R.array.config_displayWhiteBalanceDisplayPrimaries))
                .when(mMockedResources)
                .getStringArray(R.array.config_displayWhiteBalanceDisplayPrimaries);
        // Make SurfaceControl setup fail
        when(mDisplayManagerInternal.getDisplayNativePrimaries(DEFAULT_DISPLAY)).thenReturn(null);

        setUpTintController();
        assertWithMessage("Setup with valid Resources failed")
                .that(mDisplayWhiteBalanceTintController.mSetUp)
                .isTrue();
    }

    /**
     * Setup should fail when SurfaceControl setup fails and Resources result in an invalid color
     * transform.
     */
    @Test
    public void displayWhiteBalance_setupWithInvalidResources() {
        // Use Resources with invalid color data
        doReturn(new String[] {
                "0", "0", "0", // Red X, Y, Z
                "0", "0", "0", // Green X, Y, Z
                "0", "0", "0", // Blue X, Y, Z
                "0", "0", "0", // White X, Y, Z
        })
                .when(mMockedResources)
                .getStringArray(R.array.config_displayWhiteBalanceDisplayPrimaries);
        // Make SurfaceControl setup fail
        when(mDisplayManagerInternal.getDisplayNativePrimaries(DEFAULT_DISPLAY)).thenReturn(null);

        setUpTintController();
        assertWithMessage("Setup with invalid Resources succeeded")
                .that(mDisplayWhiteBalanceTintController.mSetUp)
                .isFalse();
    }

    /**
     * Matrix should match the precalculated one for given cct and display primaries.
     */
    @Test
    public void displayWhiteBalance_getAndSetMatrix_validateTransformMatrix() {
        SurfaceControl.DisplayPrimaries displayPrimaries = new SurfaceControl.DisplayPrimaries();
        displayPrimaries.red = new SurfaceControl.CieXyz();
        displayPrimaries.red.X = 0.412315f;
        displayPrimaries.red.Y = 0.212600f;
        displayPrimaries.red.Z = 0.019327f;
        displayPrimaries.green = new SurfaceControl.CieXyz();
        displayPrimaries.green.X = 0.357600f;
        displayPrimaries.green.Y = 0.715200f;
        displayPrimaries.green.Z = 0.119200f;
        displayPrimaries.blue = new SurfaceControl.CieXyz();
        displayPrimaries.blue.X = 0.180500f;
        displayPrimaries.blue.Y = 0.072200f;
        displayPrimaries.blue.Z = 0.950633f;
        displayPrimaries.white = new SurfaceControl.CieXyz();
        displayPrimaries.white.X = 0.950456f;
        displayPrimaries.white.Y = 1.000000f;
        displayPrimaries.white.Z = 1.089058f;
        when(mDisplayManagerInternal.getDisplayNativePrimaries(DEFAULT_DISPLAY))
                .thenReturn(displayPrimaries);

        setUpTintController();
        assertWithMessage("Setup with valid SurfaceControl failed")
                .that(mDisplayWhiteBalanceTintController.mSetUp)
                .isTrue();

        final int cct = 6500;
        mDisplayWhiteBalanceTintController.setMatrix(cct);
        mDisplayWhiteBalanceTintController.setAppliedCct(
                mDisplayWhiteBalanceTintController.getTargetCct());

        assertWithMessage("Failed to set temperature")
                .that(mDisplayWhiteBalanceTintController.mCurrentColorTemperature)
                .isEqualTo(cct);
        float[] matrixDwb = mDisplayWhiteBalanceTintController.getMatrix();
        final float[] expectedMatrixDwb = {
                0.971848f,   -0.001421f,  0.000491f, 0.0f,
                0.028193f,    0.945798f,  0.003207f, 0.0f,
                -0.000042f,  -0.000989f,  0.988659f, 0.0f,
                0.0f,         0.0f,       0.0f,      1.0f
        };
        assertArrayEquals("Unexpected DWB matrix", expectedMatrixDwb, matrixDwb,
                1e-6f /* tolerance */);
    }

    /**
     * Matrix should match the precalculated one for given cct and display primaries.
     */
    @Test
    public void displayWhiteBalance_targetApplied_validateTransformMatrix() {
        SurfaceControl.DisplayPrimaries displayPrimaries = new SurfaceControl.DisplayPrimaries();
        displayPrimaries.red = new SurfaceControl.CieXyz();
        displayPrimaries.red.X = 0.412315f;
        displayPrimaries.red.Y = 0.212600f;
        displayPrimaries.red.Z = 0.019327f;
        displayPrimaries.green = new SurfaceControl.CieXyz();
        displayPrimaries.green.X = 0.357600f;
        displayPrimaries.green.Y = 0.715200f;
        displayPrimaries.green.Z = 0.119200f;
        displayPrimaries.blue = new SurfaceControl.CieXyz();
        displayPrimaries.blue.X = 0.180500f;
        displayPrimaries.blue.Y = 0.072200f;
        displayPrimaries.blue.Z = 0.950633f;
        displayPrimaries.white = new SurfaceControl.CieXyz();
        displayPrimaries.white.X = 0.950456f;
        displayPrimaries.white.Y = 1.000000f;
        displayPrimaries.white.Z = 1.089058f;
        when(mDisplayManagerInternal.getDisplayNativePrimaries(DEFAULT_DISPLAY))
                .thenReturn(displayPrimaries);

        setUpTintController();
        assertWithMessage("Setup with valid SurfaceControl failed")
                .that(mDisplayWhiteBalanceTintController.mSetUp)
                .isTrue();

        final int cct = 6500;
        mDisplayWhiteBalanceTintController.setTargetCct(cct);
        final float[] matrixDwb = mDisplayWhiteBalanceTintController.computeMatrixForCct(cct);
        mDisplayWhiteBalanceTintController.setAppliedCct(cct);

        assertWithMessage("Failed to set temperature")
                .that(mDisplayWhiteBalanceTintController.mCurrentColorTemperature)
                .isEqualTo(cct);
        final float[] expectedMatrixDwb = {
                0.971848f,   -0.001421f,  0.000491f, 0.0f,
                0.028193f,    0.945798f,  0.003207f, 0.0f,
                -0.000042f,  -0.000989f,  0.988659f, 0.0f,
                0.0f,         0.0f,       0.0f,      1.0f
        };
        assertArrayEquals("Unexpected DWB matrix", expectedMatrixDwb, matrixDwb,
                1e-6f /* tolerance */);
    }

    @Test
    public void testDisplayWhiteBalance_TransitionTimes() {
        when(mDisplayManagerFlagsMock.isAdaptiveTone2Enabled()).thenReturn(false);
        setUpTransitionTimes();
        setUpTintController();

        assertEquals(30L,
                mDisplayWhiteBalanceTintController.getTransitionDurationMilliseconds(true));
        assertEquals(30L,
                mDisplayWhiteBalanceTintController.getTransitionDurationMilliseconds(false));
    }

    @Test
    public void testDisplayWhiteBalance_TransitionTimesDirectional() {
        when(mDisplayManagerFlagsMock.isAdaptiveTone2Enabled()).thenReturn(true);
        setUpTransitionTimes();
        setUpTintController();

        assertEquals(400L,
                mDisplayWhiteBalanceTintController.getTransitionDurationMilliseconds(true));
        assertEquals(5000L,
                mDisplayWhiteBalanceTintController.getTransitionDurationMilliseconds(false));
    }


    private void setUpTransitionTimes() {
        doReturn(mResources.getStringArray(R.array.config_displayWhiteBalanceDisplayPrimaries))
                .when(mMockedResources)
                .getStringArray(R.array.config_displayWhiteBalanceDisplayPrimaries);
        when(mMockedResources.getInteger(
                R.integer.config_displayWhiteBalanceTransitionTime)).thenReturn(30);
        when(mMockedResources.getInteger(
                R.integer.config_displayWhiteBalanceTransitionTimeIncrease)).thenReturn(400);
        when(mMockedResources.getInteger(
                R.integer.config_displayWhiteBalanceTransitionTimeDecrease)).thenReturn(5000);

    }

    private void setUpTintController() {
        mDisplayWhiteBalanceTintController = new DisplayWhiteBalanceTintController(
                mDisplayManagerInternal, mDisplayManagerFlagsMock);
        mDisplayWhiteBalanceTintController.setUp(mMockedContext, true);
        mDisplayWhiteBalanceTintController.setActivated(true);
    }
}
