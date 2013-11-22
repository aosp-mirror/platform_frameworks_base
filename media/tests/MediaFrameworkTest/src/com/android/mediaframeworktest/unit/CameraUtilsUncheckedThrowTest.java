/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.utils.UncheckedThrow;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.Assert;

public class CameraUtilsUncheckedThrowTest extends junit.framework.TestCase {

    private void fakeNeverThrowsCameraAccess() throws CameraAccessException {
    }

    @SmallTest
    public void testUncheckedThrow() {
        try {
            UncheckedThrow.throwAnyException(new CameraAccessException(
                    CameraAccessException.CAMERA_DISCONNECTED));
            Assert.fail("unreachable");
            fakeNeverThrowsCameraAccess();
        } catch (CameraAccessException e) {
        }
    }
}
