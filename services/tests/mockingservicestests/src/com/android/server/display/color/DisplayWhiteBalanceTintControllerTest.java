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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertArrayEquals;

import android.content.Context;
import android.content.res.Resources;
import android.os.Binder;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.view.SurfaceControl.DisplayPrimaries;
import android.view.SurfaceControl.CieXyz;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
public class DisplayWhiteBalanceTintControllerTest {
    @Mock
    private Context mMockedContext;
    @Mock
    private Resources mMockedResources;

    private MockitoSession mSession;
    private Resources mResources;
    IBinder mDisplayToken;
    DisplayWhiteBalanceTintController mDisplayWhiteBalanceTintController;

    @Before
    public void setUp() {
        mSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .mockStatic(SurfaceControl.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        mResources = InstrumentationRegistry.getContext().getResources();
        // These Resources are common to all tests.
        doReturn(mResources.getInteger(R.integer.config_displayWhiteBalanceColorTemperatureMin))
            .when(mMockedResources)
            .getInteger(R.integer.config_displayWhiteBalanceColorTemperatureMin);
        doReturn(mResources.getInteger(R.integer.config_displayWhiteBalanceColorTemperatureMax))
            .when(mMockedResources)
            .getInteger(R.integer.config_displayWhiteBalanceColorTemperatureMax);
        doReturn(mResources.getInteger(R.integer.config_displayWhiteBalanceColorTemperatureDefault))
            .when(mMockedResources)
            .getInteger(R.integer.config_displayWhiteBalanceColorTemperatureDefault);
        doReturn(mResources.getStringArray(R.array.config_displayWhiteBalanceDisplayNominalWhite))
            .when(mMockedResources)
            .getStringArray(R.array.config_displayWhiteBalanceDisplayNominalWhite);
        doReturn(mMockedResources).when(mMockedContext).getResources();

        mDisplayToken = new Binder();
        doReturn(mDisplayToken).when(() -> SurfaceControl.getInternalDisplayToken());
    }

    @After
    public void tearDown() throws Exception {
        if (mSession != null) {
            mSession.finishMocking();
        }
    }

    /**
     * Setup should succeed when SurfaceControl setup results in a valid color transform.
     */
    @Test
    public void displayWhiteBalance_setupWithSurfaceControl() {
        // Make SurfaceControl return sRGB primaries
        DisplayPrimaries displayPrimaries = new DisplayPrimaries();
        displayPrimaries.red = new CieXyz();
        displayPrimaries.red.X = 0.412315f;
        displayPrimaries.red.Y = 0.212600f;
        displayPrimaries.red.Z = 0.019327f;
        displayPrimaries.green = new CieXyz();
        displayPrimaries.green.X = 0.357600f;
        displayPrimaries.green.Y = 0.715200f;
        displayPrimaries.green.Z = 0.119200f;
        displayPrimaries.blue = new CieXyz();
        displayPrimaries.blue.X = 0.180500f;
        displayPrimaries.blue.Y = 0.072200f;
        displayPrimaries.blue.Z = 0.950633f;
        displayPrimaries.white = new CieXyz();
        displayPrimaries.white.X = 0.950456f;
        displayPrimaries.white.Y = 1.000000f;
        displayPrimaries.white.Z = 1.089058f;
        doReturn(displayPrimaries)
            .when(() -> SurfaceControl.getDisplayNativePrimaries(mDisplayToken));

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
        DisplayPrimaries displayPrimaries = new DisplayPrimaries();
        displayPrimaries.red = new CieXyz();
        displayPrimaries.green = new CieXyz();
        displayPrimaries.blue = new CieXyz();
        displayPrimaries.white = new CieXyz();
        doReturn(displayPrimaries)
            .when(() -> SurfaceControl.getDisplayNativePrimaries(mDisplayToken));

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
        doReturn(null).when(() -> SurfaceControl.getDisplayNativePrimaries(mDisplayToken));

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
        doReturn(null).when(() -> SurfaceControl.getDisplayNativePrimaries(mDisplayToken));

        setUpTintController();
        assertWithMessage("Setup with invalid Resources succeeded")
                .that(mDisplayWhiteBalanceTintController.mSetUp)
                .isFalse();
    }

    /**
     * Matrix should match the precalculated one for given cct and display primaries.
     */
    @Test
    public void displayWhiteBalance_validateTransformMatrix() {
        DisplayPrimaries displayPrimaries = new DisplayPrimaries();
        displayPrimaries.red = new CieXyz();
        displayPrimaries.red.X = 0.412315f;
        displayPrimaries.red.Y = 0.212600f;
        displayPrimaries.red.Z = 0.019327f;
        displayPrimaries.green = new CieXyz();
        displayPrimaries.green.X = 0.357600f;
        displayPrimaries.green.Y = 0.715200f;
        displayPrimaries.green.Z = 0.119200f;
        displayPrimaries.blue = new CieXyz();
        displayPrimaries.blue.X = 0.180500f;
        displayPrimaries.blue.Y = 0.072200f;
        displayPrimaries.blue.Z = 0.950633f;
        displayPrimaries.white = new CieXyz();
        displayPrimaries.white.X = 0.950456f;
        displayPrimaries.white.Y = 1.000000f;
        displayPrimaries.white.Z = 1.089058f;
        doReturn(displayPrimaries)
                .when(() -> SurfaceControl.getDisplayNativePrimaries(mDisplayToken));

        setUpTintController();
        assertWithMessage("Setup with valid SurfaceControl failed")
                .that(mDisplayWhiteBalanceTintController.mSetUp)
                .isTrue();

        final int cct = 6500;
        mDisplayWhiteBalanceTintController.setMatrix(cct);
        assertWithMessage("Failed to set temperature")
                .that(mDisplayWhiteBalanceTintController.mCurrentColorTemperature)
                .isEqualTo(cct);

        float[] matrixDwb = mDisplayWhiteBalanceTintController.getMatrix();
        final float[] expectedMatrixDwb = {
            0.962880f,  -0.001780f, -0.000158f, 0.0f,
            0.035765f,   0.929988f,  0.000858f, 0.0f,
            0.001354f,  -0.000470f,  0.948327f, 0.0f,
            0.0f,        0.0f,       0.0f,      1.0f
        };
        assertArrayEquals("Unexpected DWB matrix", matrixDwb, expectedMatrixDwb,
            1e-6f /* tolerance */);
    }

    private void setUpTintController() {
        mDisplayWhiteBalanceTintController = new DisplayWhiteBalanceTintController();
        mDisplayWhiteBalanceTintController.setUp(mMockedContext, true);
        mDisplayWhiteBalanceTintController.setActivated(true);
    }
}
