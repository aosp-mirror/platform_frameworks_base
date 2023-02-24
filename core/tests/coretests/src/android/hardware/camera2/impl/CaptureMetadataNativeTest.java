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

package android.hardware.camera2.impl;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.LensShadingMap;
import android.util.Size;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;

@SmallTest
@RunWith(JUnit4.class)
/** Tests for {@link CameraMetadataNative} class. */
public class CaptureMetadataNativeTest {

    @Test
    public void setLensShadingMap() {
        final Size s = new Size(10, 10);
        // 4 x rows x columns
        final float[] elements = new float[400];
        Arrays.fill(elements, 42);

        final LensShadingMap lensShadingMap =
                new LensShadingMap(elements, s.getHeight(), s.getWidth());
        CameraMetadataNative captureResults = new CameraMetadataNative();
        captureResults.set(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP, lensShadingMap);

        final LensShadingMap output =
                captureResults.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);

        assertThat(output).isEqualTo(lensShadingMap);
    }
}
