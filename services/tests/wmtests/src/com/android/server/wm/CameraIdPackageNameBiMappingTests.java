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

package com.android.server.wm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link CameraIdPackageNameBiMapping}.
 *
 * Build/Install/Run:
 * atest WmTests:CameraIdPackageNameBiMapTests
 */
@SmallTest
@Presubmit
public class CameraIdPackageNameBiMappingTests {
    private CameraIdPackageNameBiMapping mMapping;

    private static final String PACKAGE_1 = "PACKAGE_1";
    private static final String PACKAGE_2 = "PACKAGE_2";
    private static final String CAMERA_ID_1 = "1234";
    private static final String CAMERA_ID_2 = "5678";

    @Before
    public void setUp() {
        mMapping = new CameraIdPackageNameBiMapping();
    }

    @Test
    public void mappingEmptyAtStart() {
        assertTrue(mMapping.isEmpty());
    }

    @Test
    public void addPackageAndId_containsPackage() {
        mMapping.put(PACKAGE_1, CAMERA_ID_1);
        assertTrue(mMapping.containsPackageName(PACKAGE_1));
    }

    @Test
    public void addTwoPackagesAndId_containsPackages() {
        mMapping.put(PACKAGE_1, CAMERA_ID_1);
        mMapping.put(PACKAGE_2, CAMERA_ID_2);
        assertTrue(mMapping.containsPackageName(PACKAGE_1));
        assertTrue(mMapping.containsPackageName(PACKAGE_2));
    }

    @Test
    public void addPackageAndId_mapContainsPackageAndId() {
        mMapping.put(PACKAGE_1, CAMERA_ID_1);
        assertEquals(CAMERA_ID_1, mMapping.getCameraId(PACKAGE_1));
    }

    @Test
    public void changeCameraId_newestCameraId() {
        mMapping.put(PACKAGE_1, CAMERA_ID_1);
        mMapping.put(PACKAGE_1, CAMERA_ID_2);
        assertEquals(CAMERA_ID_2, mMapping.getCameraId(PACKAGE_1));
    }

    @Test
    public void changePackage_newestPackage() {
        mMapping.put(PACKAGE_1, CAMERA_ID_1);
        mMapping.put(PACKAGE_2, CAMERA_ID_1);
        assertFalse(mMapping.containsPackageName(PACKAGE_1));
        assertTrue(mMapping.containsPackageName(PACKAGE_2));
        assertEquals(CAMERA_ID_1, mMapping.getCameraId(PACKAGE_2));
    }

    @Test
    public void addAndRemoveCameraId_containsOtherPackage() {
        mMapping.put(PACKAGE_1, CAMERA_ID_1);
        mMapping.put(PACKAGE_2, CAMERA_ID_2);
        mMapping.removeCameraId(CAMERA_ID_1);
        assertFalse(mMapping.containsPackageName(PACKAGE_1));
        assertTrue(mMapping.containsPackageName(PACKAGE_2));
    }

    @Test
    public void addAndRemoveOnlyCameraId_empty() {
        mMapping.put(PACKAGE_1, CAMERA_ID_1);
        mMapping.removeCameraId(CAMERA_ID_1);
        assertTrue(mMapping.isEmpty());
    }
}
