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

package androidx.window.common;

import static org.junit.Assert.assertEquals;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link ExtensionHelper}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:ExtensionHelperTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ExtensionHelperTest {

    private static final int MOCK_DISPLAY_HEIGHT = 1000;
    private static final int MOCK_DISPLAY_WIDTH = 2000;
    private static final int MOCK_FEATURE_LEFT = 100;
    private static final int MOCK_FEATURE_RIGHT = 200;

    private static final int[] ROTATIONS = {
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270
    };

    private static final DisplayInfo[] MOCK_DISPLAY_INFOS = {
            getMockDisplayInfo(Surface.ROTATION_0),
            getMockDisplayInfo(Surface.ROTATION_90),
            getMockDisplayInfo(Surface.ROTATION_180),
            getMockDisplayInfo(Surface.ROTATION_270),
    };

    @Test
    public void testRotateRectToDisplayRotation() {
        for (int rotation : ROTATIONS) {
            final Rect expectedResult = getExpectedFeatureRectAfterRotation(rotation);
            // The method should return correctly rotated Rect even if the requested rotation value
            // differs from the rotation in DisplayInfo. This is because the WindowConfiguration is
            // not always synced with DisplayInfo.
            for (DisplayInfo displayInfo : MOCK_DISPLAY_INFOS) {
                final Rect rect = getMockFeatureRect();
                ExtensionHelper.rotateRectToDisplayRotation(displayInfo, rotation, rect);
                assertEquals(
                        "Result Rect should equal to expected for rotation: " + rotation
                                + "; displayInfo: " + displayInfo,
                        expectedResult, rect);
            }
        }
    }

    @NonNull
    private static DisplayInfo getMockDisplayInfo(@Surface.Rotation int rotation) {
        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.rotation = rotation;
        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            displayInfo.logicalWidth = MOCK_DISPLAY_WIDTH;
            displayInfo.logicalHeight = MOCK_DISPLAY_HEIGHT;
        } else {
            displayInfo.logicalWidth = MOCK_DISPLAY_HEIGHT;
            displayInfo.logicalHeight = MOCK_DISPLAY_WIDTH;
        }
        return displayInfo;
    }

    @NonNull
    private static Rect getMockFeatureRect() {
        return new Rect(MOCK_FEATURE_LEFT, 0, MOCK_FEATURE_RIGHT, MOCK_DISPLAY_HEIGHT);
    }

    @NonNull
    private static Rect getExpectedFeatureRectAfterRotation(@Surface.Rotation int rotation) {
        switch (rotation) {
            case Surface.ROTATION_0:
                return new Rect(
                        MOCK_FEATURE_LEFT, 0, MOCK_FEATURE_RIGHT, MOCK_DISPLAY_HEIGHT);
            case Surface.ROTATION_90:
                return new Rect(0, MOCK_DISPLAY_WIDTH - MOCK_FEATURE_RIGHT,
                        MOCK_DISPLAY_HEIGHT, MOCK_DISPLAY_WIDTH - MOCK_FEATURE_LEFT);
            case Surface.ROTATION_180:
                return new Rect(MOCK_DISPLAY_WIDTH - MOCK_FEATURE_RIGHT, 0,
                        MOCK_DISPLAY_WIDTH - MOCK_FEATURE_LEFT, MOCK_DISPLAY_HEIGHT);
            case Surface.ROTATION_270:
                return new Rect(0, MOCK_FEATURE_LEFT, MOCK_DISPLAY_HEIGHT,
                        MOCK_FEATURE_RIGHT);
            default:
                throw new IllegalArgumentException("Unknown rotation value: " + rotation);
        }
    }
}
