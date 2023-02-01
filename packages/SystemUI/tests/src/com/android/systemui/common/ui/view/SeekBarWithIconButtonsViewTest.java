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

package com.android.systemui.common.ui.view;

import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.widget.ImageView;
import android.widget.SeekBar;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link SeekBarWithIconButtonsView}
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class SeekBarWithIconButtonsViewTest extends SysuiTestCase {

    private ImageView mIconStart;
    private ImageView mIconEnd;
    private SeekBar mSeekbar;
    private SeekBarWithIconButtonsView mIconDiscreteSliderLinearLayout;

    @Before
    public void setUp() {
        mIconDiscreteSliderLinearLayout = new SeekBarWithIconButtonsView(mContext);
        mIconStart = mIconDiscreteSliderLinearLayout.findViewById(R.id.icon_start);
        mIconEnd = mIconDiscreteSliderLinearLayout.findViewById(R.id.icon_end);
        mSeekbar = mIconDiscreteSliderLinearLayout.findViewById(R.id.seekbar);
    }

    @Test
    public void setSeekBarProgressZero_startIconAndFrameDisabled() {
        mIconDiscreteSliderLinearLayout.setProgress(0);

        assertThat(mIconStart.isEnabled()).isFalse();
        assertThat(mIconEnd.isEnabled()).isTrue();
    }

    @Test
    public void setSeekBarProgressMax_endIconAndFrameDisabled() {
        mIconDiscreteSliderLinearLayout.setProgress(mSeekbar.getMax());

        assertThat(mIconEnd.isEnabled()).isFalse();
        assertThat(mIconStart.isEnabled()).isTrue();
    }

    @Test
    public void setSeekBarProgressMax_allIconsAndFramesEnabled() {
        // We are using the default value for the max of seekbar.
        // Therefore, the max value will be DEFAULT_SEEKBAR_MAX = 6.
        mIconDiscreteSliderLinearLayout.setProgress(1);

        assertThat(mIconStart.isEnabled()).isTrue();
        assertThat(mIconEnd.isEnabled()).isTrue();
    }

    @Test
    public void clickIconEnd_currentProgressIsOneToMax_reachesMax() {
        mIconDiscreteSliderLinearLayout.setProgress(mSeekbar.getMax() - 1);
        mIconEnd.performClick();

        assertThat(mSeekbar.getProgress()).isEqualTo(mSeekbar.getMax());
    }

    @Test
    public void clickIconStart_currentProgressIsOne_reachesZero() {
        mIconDiscreteSliderLinearLayout.setProgress(1);
        mIconStart.performClick();

        assertThat(mSeekbar.getProgress()).isEqualTo(0);
    }
}
