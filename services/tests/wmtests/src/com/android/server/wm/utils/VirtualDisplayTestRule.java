/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.server.wm.utils;

import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertNotNull;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Provides wrapper test rule for creating and managing the cleanup for VirtualDisplay */
public class VirtualDisplayTestRule implements TestRule {
    private static final int DISPLAY_DENSITY = 160;

    private final List<VirtualDisplay> mVirtualDisplays = new ArrayList<>();
    private final List<ImageReader> mImageReaders = new ArrayList<>();
    private final DisplayManager mDisplayManager;

    public VirtualDisplayTestRule() {
        mDisplayManager = getInstrumentation().getTargetContext().getSystemService(
                DisplayManager.class);
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    base.evaluate();
                } finally {
                    tearDown();
                }
            }
        };
    }

    private void tearDown() {
        mVirtualDisplays.forEach(VirtualDisplay::release);
        mImageReaders.forEach(ImageReader::close);
    }

    /**
     * The virtual display in WindowTestsBase#createMockSimulatedDisplay is only attached to WM
     * DisplayWindowSettingsProvider. DisplayManager is not aware of mock simulated display and
     * therefore couldn't be used for actual Display-related testing (e.g. display listeners).
     * This method creates real VirtualDisplay through DisplayManager.
     */
    public VirtualDisplay createDisplayManagerAttachedVirtualDisplay(String name, int width,
            int height) {
        final ImageReader imageReader = ImageReader.newInstance(width, height,
                PixelFormat.RGBA_8888, 2 /* maxImages */);
        mImageReaders.add(imageReader);
        final int flags = VIRTUAL_DISPLAY_FLAG_PRESENTATION | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | VIRTUAL_DISPLAY_FLAG_PUBLIC;
        final VirtualDisplay virtualDisplay = mDisplayManager.createVirtualDisplay(name, width,
                height, DISPLAY_DENSITY, imageReader.getSurface(), flags);
        mVirtualDisplays.add(virtualDisplay);
        assertNotNull("display must be registered", Arrays.stream(
                mDisplayManager.getDisplays()).filter(d -> d.getName().equals(name)).findAny());
        return virtualDisplay;
    }
}
