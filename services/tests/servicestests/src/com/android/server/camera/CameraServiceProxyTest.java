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

package com.android.server.camera;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.InstrumentationRegistry;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.view.Display;
import android.view.Surface;

import java.util.Map;

@RunWith(JUnit4.class)
public class CameraServiceProxyTest {

    @Test
    public void testGetCropRotateScale() {

        Context ctx = InstrumentationRegistry.getContext();

        // Check resizeability and SDK
        CameraServiceProxy.TaskInfo taskInfo = new CameraServiceProxy.TaskInfo();
        taskInfo.isResizeable = true;
        taskInfo.displayId = Display.DEFAULT_DISPLAY;
        taskInfo.isFixedOrientationLandscape = false;
        taskInfo.isFixedOrientationPortrait = true;
        // Resizeable apps should be ignored
        assertThat(CameraServiceProxy.getCropRotateScale(ctx, ctx.getPackageName(), taskInfo,
                Surface.ROTATION_90 , CameraCharacteristics.LENS_FACING_BACK,
                /*ignoreResizableAndSdkCheck*/false)).isEqualTo(
                CameraMetadata.SCALER_ROTATE_AND_CROP_NONE);
        // Resizeable apps will be considered in case the ignore flag is set
        assertThat(CameraServiceProxy.getCropRotateScale(ctx, ctx.getPackageName(), taskInfo,
                Surface.ROTATION_90, CameraCharacteristics.LENS_FACING_BACK,
                /*ignoreResizableAndSdkCheck*/true)).isEqualTo(
                CameraMetadata.SCALER_ROTATE_AND_CROP_90);
        taskInfo.isResizeable = false;
        // Non-resizeable apps should be considered
        assertThat(CameraServiceProxy.getCropRotateScale(ctx, ctx.getPackageName(), taskInfo,
                Surface.ROTATION_90, CameraCharacteristics.LENS_FACING_BACK,
                /*ignoreResizableAndSdkCheck*/false)).isEqualTo(
                CameraMetadata.SCALER_ROTATE_AND_CROP_90);
        // The ignore flag for non-resizeable should have no effect
        assertThat(CameraServiceProxy.getCropRotateScale(ctx, ctx.getPackageName(), taskInfo,
                Surface.ROTATION_90, CameraCharacteristics.LENS_FACING_BACK,
                /*ignoreResizableAndSdkCheck*/true)).isEqualTo(
                CameraMetadata.SCALER_ROTATE_AND_CROP_90);
        // Non-fixed orientation should be ignored
        taskInfo.isFixedOrientationLandscape = false;
        taskInfo.isFixedOrientationPortrait = false;
        assertThat(CameraServiceProxy.getCropRotateScale(ctx, ctx.getPackageName(), taskInfo,
                Surface.ROTATION_90, CameraCharacteristics.LENS_FACING_BACK,
                /*ignoreResizableAndSdkCheck*/true)).isEqualTo(
                CameraMetadata.SCALER_ROTATE_AND_CROP_NONE);
        // Check rotation and lens facing combinations
        Map<Integer, Integer> backFacingMap = Map.of(
                Surface.ROTATION_0, CameraMetadata.SCALER_ROTATE_AND_CROP_NONE,
                Surface.ROTATION_90, CameraMetadata.SCALER_ROTATE_AND_CROP_90,
                Surface.ROTATION_270, CameraMetadata.SCALER_ROTATE_AND_CROP_270,
                Surface.ROTATION_180, CameraMetadata.SCALER_ROTATE_AND_CROP_180);
        taskInfo.isFixedOrientationPortrait = true;
        backFacingMap.forEach((key, value) -> {
            assertThat(CameraServiceProxy.getCropRotateScale(ctx, ctx.getPackageName(), taskInfo,
                    key, CameraCharacteristics.LENS_FACING_BACK,
                    /*ignoreResizableAndSdkCheck*/true)).isEqualTo(value);
        });
        Map<Integer, Integer> frontFacingMap = Map.of(
                Surface.ROTATION_0, CameraMetadata.SCALER_ROTATE_AND_CROP_NONE,
                Surface.ROTATION_90, CameraMetadata.SCALER_ROTATE_AND_CROP_270,
                Surface.ROTATION_270, CameraMetadata.SCALER_ROTATE_AND_CROP_90,
                Surface.ROTATION_180, CameraMetadata.SCALER_ROTATE_AND_CROP_180);
        frontFacingMap.forEach((key, value) -> {
            assertThat(CameraServiceProxy.getCropRotateScale(ctx, ctx.getPackageName(), taskInfo,
                    key, CameraCharacteristics.LENS_FACING_FRONT,
                    /*ignoreResizableAndSdkCheck*/true)).isEqualTo(value);
        });
    }
}
