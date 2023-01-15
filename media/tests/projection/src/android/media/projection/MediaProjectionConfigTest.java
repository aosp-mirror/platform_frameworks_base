/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.media.projection;

import static android.media.projection.MediaProjectionConfig.CAPTURE_REGION_FIXED_DISPLAY;
import static android.media.projection.MediaProjectionConfig.CAPTURE_REGION_USER_CHOICE;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link MediaProjectionConfig} class.
 *
 * Build/Install/Run:
 * atest MediaProjectionTests:MediaProjectionConfigTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class MediaProjectionConfigTest {
    private static final MediaProjectionConfig DISPLAY_CONFIG =
            MediaProjectionConfig.createConfigForDefaultDisplay();
    private static final MediaProjectionConfig USERS_CHOICE_CONFIG =
            MediaProjectionConfig.createConfigForUserChoice();

    @Test
    public void testParcelable() {
        Parcel parcel = Parcel.obtain();
        DISPLAY_CONFIG.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);
        MediaProjectionConfig config = MediaProjectionConfig.CREATOR.createFromParcel(parcel);
        assertThat(DISPLAY_CONFIG).isEqualTo(config);
        parcel.recycle();
    }

    @Test
    public void testCreateDisplayConfig() {
        assertThat(DISPLAY_CONFIG.getRegionToCapture()).isEqualTo(CAPTURE_REGION_FIXED_DISPLAY);
        assertThat(DISPLAY_CONFIG.getDisplayToCapture()).isEqualTo(DEFAULT_DISPLAY);
    }

    @Test
    public void testCreateUsersChoiceConfig() {
        assertThat(USERS_CHOICE_CONFIG.getRegionToCapture()).isEqualTo(CAPTURE_REGION_USER_CHOICE);
    }

    @Test
    public void testEquals() {
        assertThat(MediaProjectionConfig.createConfigForUserChoice()).isEqualTo(
                USERS_CHOICE_CONFIG);
        assertThat(DISPLAY_CONFIG).isNotEqualTo(USERS_CHOICE_CONFIG);
        assertThat(MediaProjectionConfig.createConfigForDefaultDisplay()).isEqualTo(
                DISPLAY_CONFIG);
    }
}
