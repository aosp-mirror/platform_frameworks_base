/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.companion.virtual.camera;

import android.companion.virtual.camera.VirtualCameraStreamConfig;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class VirtualCameraStreamConfigTest {

    private static final int VGA_WIDTH = 640;
    private static final int VGA_HEIGHT = 480;
    private static final int MAX_FPS_1 = 30;

    private static final int QVGA_WIDTH = 320;
    private static final int QVGA_HEIGHT = 240;
    private static final int MAX_FPS_2 = 60;

    @Test
    public void testEquals() {
        VirtualCameraStreamConfig vgaYuvStreamConfig = new VirtualCameraStreamConfig(VGA_WIDTH,
                VGA_HEIGHT, ImageFormat.YUV_420_888, MAX_FPS_1);
        VirtualCameraStreamConfig qvgaYuvStreamConfig = new VirtualCameraStreamConfig(QVGA_WIDTH,
                QVGA_HEIGHT, ImageFormat.YUV_420_888, MAX_FPS_2);
        VirtualCameraStreamConfig vgaRgbaStreamConfig = new VirtualCameraStreamConfig(VGA_WIDTH,
                VGA_HEIGHT, PixelFormat.RGBA_8888, MAX_FPS_1);

        new EqualsTester()
                .addEqualityGroup(vgaYuvStreamConfig, reparcel(vgaYuvStreamConfig))
                .addEqualityGroup(qvgaYuvStreamConfig, reparcel(qvgaYuvStreamConfig))
                .addEqualityGroup(vgaRgbaStreamConfig, reparcel(vgaRgbaStreamConfig))
                .testEquals();
    }

    private static VirtualCameraStreamConfig reparcel(VirtualCameraStreamConfig config) {
        Parcel parcel = Parcel.obtain();
        try {
            config.writeToParcel(parcel, /* flags= */ 0);
            parcel.setDataPosition(0);
            return VirtualCameraStreamConfig.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }
}
