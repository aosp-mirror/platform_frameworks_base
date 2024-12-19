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

package com.android.systemui.accessibility.hearingaid;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link AmbientVolumeLayout}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class AmbientVolumeSliderTest extends SysuiTestCase {

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Spy
    private Context mContext = ApplicationProvider.getApplicationContext();

    private AmbientVolumeSlider mSlider;

    @Before
    public void setUp() {
        mSlider = new AmbientVolumeSlider(mContext);
    }

    @Test
    public void setTitle_titleCorrect() {
        final String testTitle = "test";
        mSlider.setTitle(testTitle);

        assertThat(mSlider.getTitle()).isEqualTo(testTitle);
    }

    @Test
    public void getVolumeLevel_valueMin_volumeLevelIsZero() {
        prepareSlider(/* min= */ 0, /* max= */ 100, /* value= */ 0);

        // The volume level is divided into 5 levels:
        // Level 0 corresponds to the minimum volume value. The range between the minimum and
        // maximum volume is divided into 4 equal intervals, represented by levels 1 to 4.
        assertThat(mSlider.getVolumeLevel()).isEqualTo(0);
    }

    @Test
    public void getVolumeLevel_valueMax_volumeLevelIsFour() {
        prepareSlider(/* min= */ 0, /* max= */ 100, /* value= */ 100);

        assertThat(mSlider.getVolumeLevel()).isEqualTo(4);
    }

    @Test
    public void getVolumeLevel_volumeLevelIsCorrect() {
        prepareSlider(/* min= */ 0, /* max= */ 100, /* value= */ 73);

        assertThat(mSlider.getVolumeLevel()).isEqualTo(3);
    }

    private void prepareSlider(float min, float max, float value) {
        mSlider.setMin(min);
        mSlider.setMax(max);
        mSlider.setValue(value);
    }
}
