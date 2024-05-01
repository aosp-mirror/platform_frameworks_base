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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.common.ui.view.SeekBarWithIconButtonsView.OnSeekBarWithIconButtonsChangeListener;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link SeekBarWithIconButtonsView}
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class SeekBarWithIconButtonsViewTest extends SysuiTestCase {

    private ImageView mIconStart;
    private ImageView mIconEnd;
    private ViewGroup mIconStartFrame;
    private ViewGroup mIconEndFrame;
    private SeekBar mSeekbar;
    private SeekBarWithIconButtonsView mIconDiscreteSliderLinearLayout;

    @Mock
    private OnSeekBarWithIconButtonsChangeListener mOnSeekBarChangeListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mIconDiscreteSliderLinearLayout = new SeekBarWithIconButtonsView(mContext);
        mIconStart = mIconDiscreteSliderLinearLayout.findViewById(R.id.icon_start);
        mIconEnd = mIconDiscreteSliderLinearLayout.findViewById(R.id.icon_end);
        mIconStartFrame = mIconDiscreteSliderLinearLayout.findViewById(R.id.icon_start_frame);
        mIconEndFrame = mIconDiscreteSliderLinearLayout.findViewById(R.id.icon_end_frame);
        mSeekbar = mIconDiscreteSliderLinearLayout.findViewById(R.id.seekbar);

        mIconDiscreteSliderLinearLayout.setOnSeekBarWithIconButtonsChangeListener(
                mOnSeekBarChangeListener);
    }

    @Test
    public void setSeekBarProgressZero_startIconAndFrameDisabled() {
        mIconDiscreteSliderLinearLayout.setProgress(0);

        assertThat(mIconStart.isEnabled()).isFalse();
        assertThat(mIconEnd.isEnabled()).isTrue();
        assertThat(mIconStartFrame.isEnabled()).isFalse();
        assertThat(mIconEndFrame.isEnabled()).isTrue();
    }

    @Test
    public void setSeekBarProgressMax_endIconAndFrameDisabled() {
        mIconDiscreteSliderLinearLayout.setProgress(mSeekbar.getMax());

        assertThat(mIconEnd.isEnabled()).isFalse();
        assertThat(mIconStart.isEnabled()).isTrue();
        assertThat(mIconEndFrame.isEnabled()).isFalse();
        assertThat(mIconStartFrame.isEnabled()).isTrue();
    }

    @Test
    public void setSeekBarProgressMax_allIconsAndFramesEnabled() {
        // We are using the default value for the max of seekbar.
        // Therefore, the max value will be DEFAULT_SEEKBAR_MAX = 6.
        mIconDiscreteSliderLinearLayout.setProgress(1);

        assertThat(mIconStart.isEnabled()).isTrue();
        assertThat(mIconEnd.isEnabled()).isTrue();
        assertThat(mIconStartFrame.isEnabled()).isTrue();
        assertThat(mIconEndFrame.isEnabled()).isTrue();
    }

    @Test
    public void clickIconEnd_currentProgressIsOneToMax_reachesMax() {
        mIconDiscreteSliderLinearLayout.setProgress(mSeekbar.getMax() - 1);

        mIconEndFrame.performClick();

        assertThat(mSeekbar.getProgress()).isEqualTo(mSeekbar.getMax());
    }

    @Test
    public void clickIconStart_currentProgressIsOne_reachesZero() {
        mIconDiscreteSliderLinearLayout.setProgress(1);

        mIconStartFrame.performClick();

        assertThat(mSeekbar.getProgress()).isEqualTo(0);
    }

    @Test
    public void setProgress_onlyOnProgressChangedTriggeredWithFromUserFalse() {
        reset(mOnSeekBarChangeListener);
        mIconDiscreteSliderLinearLayout.setProgress(1);

        verify(mOnSeekBarChangeListener).onProgressChanged(
                eq(mSeekbar), /* progress= */ eq(1), /* fromUser= */ eq(false));
        verify(mOnSeekBarChangeListener, never()).onStartTrackingTouch(/* seekBar= */ any());
        verify(mOnSeekBarChangeListener, never()).onStopTrackingTouch(/* seekBar= */ any());
        verify(mOnSeekBarChangeListener, never()).onUserInteractionFinalized(
                /* seekBar= */any(), /* control= */ anyInt());
    }

    @Test
    public void clickIconEnd_triggerCallbacksInSequence() {
        final int magnitude = mIconDiscreteSliderLinearLayout.getChangeMagnitude();
        mIconDiscreteSliderLinearLayout.setProgress(0);
        reset(mOnSeekBarChangeListener);

        mIconEndFrame.performClick();

        InOrder inOrder = Mockito.inOrder(mOnSeekBarChangeListener);
        inOrder.verify(mOnSeekBarChangeListener).onProgressChanged(
                eq(mSeekbar), /* progress= */ eq(magnitude), /* fromUser= */ eq(true));
        inOrder.verify(mOnSeekBarChangeListener).onUserInteractionFinalized(
                eq(mSeekbar), eq(OnSeekBarWithIconButtonsChangeListener.ControlUnitType.BUTTON));
    }

    @Test
    public void clickIconStart_triggerCallbacksInSequence() {
        final int magnitude = mIconDiscreteSliderLinearLayout.getChangeMagnitude();
        mIconDiscreteSliderLinearLayout.setProgress(magnitude);
        reset(mOnSeekBarChangeListener);

        mIconStartFrame.performClick();

        InOrder inOrder = Mockito.inOrder(mOnSeekBarChangeListener);
        inOrder.verify(mOnSeekBarChangeListener).onProgressChanged(
                eq(mSeekbar), /* progress= */ eq(0), /* fromUser= */ eq(true));
        inOrder.verify(mOnSeekBarChangeListener).onUserInteractionFinalized(
                eq(mSeekbar), eq(OnSeekBarWithIconButtonsChangeListener.ControlUnitType.BUTTON));
    }

    @Test
    public void setProgressStateLabels_getExpectedStateDescriptionOnInitialization() {
        String[] stateLabels = new String[]{"1", "2", "3", "4", "5"};
        mIconDiscreteSliderLinearLayout.setMax(stateLabels.length);
        mIconDiscreteSliderLinearLayout.setProgress(1);
        mIconDiscreteSliderLinearLayout.setProgressStateLabels(stateLabels);

        final int currentProgress = mSeekbar.getProgress();
        final CharSequence stateDescription = mSeekbar.getStateDescription();

        assertThat(currentProgress).isEqualTo(1);
        assertThat(stateDescription).isEqualTo(stateLabels[currentProgress]);
    }

    @Test
    public void setProgressStateLabels_progressChanged_getExpectedStateDescription() {
        String[] stateLabels = new String[]{"1", "2", "3", "4", "5"};
        mIconDiscreteSliderLinearLayout.setMax(stateLabels.length);
        mIconDiscreteSliderLinearLayout.setProgressStateLabels(stateLabels);
        mIconDiscreteSliderLinearLayout.setProgress(1);

        final int currentProgress = mSeekbar.getProgress();
        final CharSequence stateDescription = mSeekbar.getStateDescription();

        assertThat(currentProgress).isEqualTo(1);
        assertThat(stateDescription).isEqualTo(stateLabels[currentProgress]);
    }
}
