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

package com.android.overlaytest.remounted;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class OverlaySharedLibraryTest extends OverlayRemountedTestBase {
    private static final String SHARED_LIBRARY_APK =
            "OverlayRemountedTest_SharedLibrary.apk";
    private static final String SHARED_LIBRARY_PACKAGE =
            "com.android.overlaytest.remounted.shared_library";
    private static final String SHARED_LIBRARY_OVERLAY_APK =
            "OverlayRemountedTest_SharedLibraryOverlay.apk";
    private static final String SHARED_LIBRARY_OVERLAY_PACKAGE =
            "com.android.overlaytest.remounted.shared_library.overlay";

    @Test
    public void testSharedLibrary() throws Exception {
        final String targetResource = resourceName(TARGET_PACKAGE, "bool",
                "uses_shared_library_overlaid");
        final String libraryResource = resourceName(SHARED_LIBRARY_PACKAGE, "bool",
                "shared_library_overlaid");

        mPreparer.pushResourceFile(SHARED_LIBRARY_APK, "/product/app/SharedLibrary.apk")
                .installResourceApk(SHARED_LIBRARY_OVERLAY_APK, SHARED_LIBRARY_OVERLAY_PACKAGE)
                .reboot()
                .setOverlayEnabled(SHARED_LIBRARY_OVERLAY_PACKAGE, false)
                .installResourceApk(TARGET_APK, TARGET_PACKAGE);

        // The shared library resource is not currently overlaid.
        assertResource(targetResource, "false");
        assertResource(libraryResource, "false");

        // Overlay the shared library resource.
        mPreparer.setOverlayEnabled(SHARED_LIBRARY_OVERLAY_PACKAGE, true);
        assertResource(targetResource, "true");
        assertResource(libraryResource, "true");
    }

    @Test
    public void testSharedLibraryPreEnabled() throws Exception {
        final String targetResource = resourceName(TARGET_PACKAGE, "bool",
                "uses_shared_library_overlaid");
        final String libraryResource = resourceName(SHARED_LIBRARY_PACKAGE, "bool",
                "shared_library_overlaid");

        mPreparer.pushResourceFile(SHARED_LIBRARY_APK, "/product/app/SharedLibrary.apk")
                .installResourceApk(SHARED_LIBRARY_OVERLAY_APK, SHARED_LIBRARY_OVERLAY_PACKAGE)
                .setOverlayEnabled(SHARED_LIBRARY_OVERLAY_PACKAGE, true)
                .reboot()
                .installResourceApk(TARGET_APK, TARGET_PACKAGE);

        assertResource(targetResource, "true");
        assertResource(libraryResource, "true");
    }
}
