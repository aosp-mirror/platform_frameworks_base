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

package com.android.overlaytest;

import android.support.test.filters.MediumTest;

import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@MediumTest
public class WithMultipleOverlaysTest extends OverlayBaseTest {
    public WithMultipleOverlaysTest() {
        super(MODE_MULTIPLE_OVERLAYS);
    }

    @BeforeClass
    public static void enableOverlay() throws Exception {
        enableOverlayPackages(APP_OVERLAY_ONE_PKG, APP_OVERLAY_TWO_PKG, FRAMEWORK_OVERLAY_PKG);
    }
}
