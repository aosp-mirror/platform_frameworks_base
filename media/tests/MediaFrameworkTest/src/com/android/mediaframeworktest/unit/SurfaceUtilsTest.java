/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.mediaframeworktest.unit;

import android.graphics.ImageFormat;
import android.hardware.camera2.utils.SurfaceUtils;
import android.media.ImageReader;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.Surface;

import junit.framework.Assert;

public class SurfaceUtilsTest extends junit.framework.TestCase {

    @SmallTest
    public void testInvalidSurfaceException() {
        ImageReader reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 1);
        Surface surface = reader.getSurface();
        surface.release();

        try {
            SurfaceUtils.isFlexibleConsumer(surface);
            Assert.fail("unreachable");
        } catch (UnsupportedOperationException e) {
            // expected
        }

        reader.close();
    }
}
