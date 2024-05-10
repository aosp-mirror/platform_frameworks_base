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

package android.hardware.display;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.SurfaceTexture;
import android.os.Parcel;
import android.util.DisplayMetrics;
import android.view.Surface;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;

/**
 * Tests for non-public APIs in {@link VirtualDisplayConfig}.
 * See also related CTS tests.
 *
 * Run with:
 * atest FrameworksCoreTests:VirtualDisplayConfigTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class VirtualDisplayConfigTest {

    private static final String NAME = "VirtualDisplayConfigTest";
    private static final int WIDTH = 720;
    private static final int HEIGHT = 480;
    private static final int DENSITY = DisplayMetrics.DENSITY_MEDIUM;
    private static final float REQUESTED_REFRESH_RATE = 30.0f;
    private static final int FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;

    // Values for hidden APIs.
    private static final int DISPLAY_ID_TO_MIRROR = 10;

    private final Surface mSurface = new Surface(new SurfaceTexture(/*texName=*/1));

    @Test
    public void testParcelAndUnparcel_matches() {
        final VirtualDisplayConfig originalConfig = buildGenericVirtualDisplay(NAME);

        validateConstantFields(originalConfig);
        assertThat(originalConfig.getName()).isEqualTo(NAME);


        final Parcel parcel = Parcel.obtain();
        originalConfig.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);
        final VirtualDisplayConfig recreatedConfig =
                VirtualDisplayConfig.CREATOR.createFromParcel(parcel);

        validateConstantFields(recreatedConfig);
        assertThat(recreatedConfig.getName()).isEqualTo(NAME);
    }

    @Test
    public void testEquals_matches() {
        assertThat(buildGenericVirtualDisplay(NAME)).isEqualTo(buildGenericVirtualDisplay(NAME));
    }

    @Test
    public void testEquals_different() {
        assertThat(buildGenericVirtualDisplay(NAME + "2")).isNotEqualTo(
                buildGenericVirtualDisplay(NAME));
    }

    private VirtualDisplayConfig buildGenericVirtualDisplay(String name) {
        return new VirtualDisplayConfig.Builder(name, WIDTH, HEIGHT, DENSITY)
                .setFlags(FLAGS)
                .setSurface(mSurface)
                .setDisplayCategories(Set.of("C1", "C2"))
                .addDisplayCategory("C3")
                .setRequestedRefreshRate(REQUESTED_REFRESH_RATE)
                .setDisplayIdToMirror(DISPLAY_ID_TO_MIRROR)
                .setWindowManagerMirroringEnabled(true)
                .build();
    }

    private void validateConstantFields(VirtualDisplayConfig config) {
        assertThat(config.getWidth()).isEqualTo(WIDTH);
        assertThat(config.getHeight()).isEqualTo(HEIGHT);
        assertThat(config.getDensityDpi()).isEqualTo(DENSITY);
        assertThat(config.getFlags()).isEqualTo(FLAGS);
        assertThat(config.getSurface()).isNotNull();
        assertThat(config.getDisplayCategories()).containsExactly("C1", "C2", "C3");
        assertThat(config.getRequestedRefreshRate()).isEqualTo(REQUESTED_REFRESH_RATE);
        assertThat(config.getDisplayIdToMirror()).isEqualTo(DISPLAY_ID_TO_MIRROR);
        assertThat(config.isWindowManagerMirroringEnabled()).isTrue();
    }
}
