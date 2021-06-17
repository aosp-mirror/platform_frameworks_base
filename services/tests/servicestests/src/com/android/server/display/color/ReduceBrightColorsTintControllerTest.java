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

package com.android.server.display.color;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ReduceBrightColorsTintControllerTest {

    private Context mContext;

    @Before
    public void setUp() {
        final Resources mockResources = mock(Resources.class);
        when(mockResources.getStringArray(
                com.android.internal.R.array.config_reduceBrightColorsCoefficients))
                .thenReturn(new String[]{"-0.000000000000001", "-0.955555555555554",
                        "1.000000000000000"});
        when(mockResources.getStringArray(
                com.android.internal.R.array.config_reduceBrightColorsCoefficientsNonlinear))
                .thenReturn(new String[]{"-0.4429953456", "-0.2434077725", "0.9809063061"});
        mContext = mock(Context.class);
        when(mContext.getResources()).thenReturn(mockResources);
    }

    @Test
    public void setAndGetMatrix() {
        final ReduceBrightColorsTintController tintController =
                new ReduceBrightColorsTintController();
        tintController.setUp(mContext, /* needsLinear= */ true);
        tintController.setMatrix(50);
        tintController.setActivated(true);
        assertThat(tintController.getStrength()).isEqualTo(50);
        assertThat(tintController.getMatrix()).usingTolerance(0.00001f)
                .containsExactly(
                        0.5222222f, 0f, 0f, 0f,
                        0f, 0.5222222f, 0f, 0f,
                        0f, 0f, 0.5222222f, 0f,
                        0f, 0f, 0f, 1f)
                .inOrder();
    }

    @Test
    public void setAndGetMatrixClampToZero() {
        final ReduceBrightColorsTintController tintController =
                new ReduceBrightColorsTintController();
        tintController.setUp(mContext, /* needsLinear= */ true);
        tintController.setMatrix(-50);
        tintController.setActivated(true);
        assertThat(tintController.getStrength()).isEqualTo(0);
        assertThat(tintController.getMatrix()).usingTolerance(0.00001f)
                .containsExactly(
                        1f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f,
                        0f, 0f, 1f, 0f,
                        0f, 0f, 0f, 1f)
                .inOrder();
    }

    @Test
    public void setAndGetMatrixClampTo100() {
        final ReduceBrightColorsTintController tintController =
                new ReduceBrightColorsTintController();
        tintController.setUp(mContext, /* needsLinear= */ true);
        tintController.setMatrix(120);
        tintController.setActivated(true);
        assertThat(tintController.getStrength()).isEqualTo(100);
        assertThat(tintController.getMatrix()).usingTolerance(0.00001f)
                .containsExactly(
                        0.04444444f, 0f, 0f, 0f,
                        0f, 0.04444444f, 0f, 0f,
                        0f, 0f, 0.04444444f, 0f,
                        0f, 0f, 0f, 1f)
                .inOrder();
    }

    @Test
    public void returnsIdentityMatrixWhenNotActivated() {
        final ReduceBrightColorsTintController tintController =
                new ReduceBrightColorsTintController();
        tintController.setUp(mContext, /* needsLinear= */ true);
        tintController.setMatrix(50);
        tintController.setActivated(true);
        tintController.setActivated(false);
        assertThat(tintController.getStrength()).isEqualTo(50);
        assertThat(tintController.getMatrix()).usingTolerance(0.00001f)
                .containsExactly(
                        1f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f,
                        0f, 0f, 1f, 0f,
                        0f, 0f, 0f, 1f)
                .inOrder();
    }

    @Test
    public void getAdjustedBrightnessZeroRbcStrengthFullBrightness() {
        final ReduceBrightColorsTintController tintController =
                new ReduceBrightColorsTintController();
        tintController.setUp(mContext, /* needsLinear= */ true);
        tintController.setMatrix(0);
        assertThat(tintController.getAdjustedBrightness(450f)).isEqualTo(450f);
    }

    @Test
    public void getAdjustedBrightnessFullRbcStrengthFullBrightness() {
        final ReduceBrightColorsTintController tintController =
                new ReduceBrightColorsTintController();
        tintController.setUp(mContext, /* needsLinear= */ true);
        tintController.setMatrix(100);
        assertThat(tintController.getAdjustedBrightness(450f)).isEqualTo(19.999998f);
    }

    @Test
    public void getAdjustedBrightnessZeroRbcStrengthLowBrightness() {
        final ReduceBrightColorsTintController tintController =
                new ReduceBrightColorsTintController();
        tintController.setUp(mContext, /* needsLinear= */ true);
        tintController.setMatrix(0);
        assertThat(tintController.getAdjustedBrightness(2.2f)).isEqualTo(2.2f);
    }

    @Test
    public void getAdjustedBrightnessFullRbcStrengthLowBrightness() {
        final ReduceBrightColorsTintController tintController =
                new ReduceBrightColorsTintController();
        tintController.setUp(mContext, /* needsLinear= */ true);
        tintController.setMatrix(100);
        assertThat(tintController.getAdjustedBrightness(2.2f)).isEqualTo(0.09777778f);
    }
}
