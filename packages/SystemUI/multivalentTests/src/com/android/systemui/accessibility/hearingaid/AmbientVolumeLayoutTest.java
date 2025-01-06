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

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static com.android.settingslib.bluetooth.HearingAidInfo.DeviceSide.SIDE_LEFT;
import static com.android.settingslib.bluetooth.HearingAidInfo.DeviceSide.SIDE_RIGHT;
import static com.android.systemui.accessibility.hearingaid.AmbientVolumeLayout.ROTATION_COLLAPSED;
import static com.android.systemui.accessibility.hearingaid.AmbientVolumeLayout.ROTATION_EXPANDED;
import static com.android.systemui.accessibility.hearingaid.AmbientVolumeLayout.SIDE_UNIFIED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.ArrayMap;
import android.view.View;
import android.widget.ImageView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.settingslib.bluetooth.AmbientVolumeUi;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Map;

/** Tests for {@link AmbientVolumeLayout}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class AmbientVolumeLayoutTest extends SysuiTestCase {

    private static final int TEST_LEFT_VOLUME_LEVEL = 1;
    private static final int TEST_RIGHT_VOLUME_LEVEL = 2;
    private static final int TEST_UNIFIED_VOLUME_LEVEL = 3;

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Spy
    private Context mContext = ApplicationProvider.getApplicationContext();
    @Mock
    private AmbientVolumeUi.AmbientVolumeUiListener mListener;

    private AmbientVolumeLayout mLayout;
    private ImageView mExpandIcon;
    private ImageView mVolumeIcon;
    private final Map<Integer, BluetoothDevice> mSideToDeviceMap = new ArrayMap<>();

    @Before
    public void setUp() {
        mLayout = new AmbientVolumeLayout(mContext);
        mLayout.setListener(mListener);
        mLayout.setExpandable(true);
        mLayout.setMutable(true);

        prepareDevices();
        mLayout.setupSliders(mSideToDeviceMap);
        mLayout.getSliders().forEach((side, slider) -> {
            slider.setMin(0);
            slider.setMax(4);
            if (side == SIDE_LEFT) {
                slider.setValue(TEST_LEFT_VOLUME_LEVEL);
            } else if (side == SIDE_RIGHT) {
                slider.setValue(TEST_RIGHT_VOLUME_LEVEL);
            } else if (side == SIDE_UNIFIED) {
                slider.setValue(TEST_UNIFIED_VOLUME_LEVEL);
            }
        });

        mExpandIcon = mLayout.getExpandIcon();
        mVolumeIcon = mLayout.getVolumeIcon();
    }

    @Test
    public void setExpandable_expandable_expandIconVisible() {
        mLayout.setExpandable(true);

        assertThat(mExpandIcon.getVisibility()).isEqualTo(VISIBLE);
    }

    @Test
    public void setExpandable_notExpandable_expandIconGone() {
        mLayout.setExpandable(false);

        assertThat(mExpandIcon.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void setExpanded_expanded_assertControlUiCorrect() {
        mLayout.setExpanded(true);

        assertControlUiCorrect();
    }

    @Test
    public void setExpanded_notExpanded_assertControlUiCorrect() {
        mLayout.setExpanded(false);

        assertControlUiCorrect();
    }

    @Test
    public void setMutable_mutable_clickOnMuteIconChangeMuteState() {
        mLayout.setMutable(true);
        mLayout.setMuted(false);

        mVolumeIcon.callOnClick();

        assertThat(mLayout.isMuted()).isTrue();
    }

    @Test
    public void setMutable_notMutable_clickOnMuteIconWontChangeMuteState() {
        mLayout.setMutable(false);
        mLayout.setMuted(false);

        mVolumeIcon.callOnClick();

        assertThat(mLayout.isMuted()).isFalse();
    }

    @Test
    public void updateLayout_mute_volumeIconIsCorrect() {
        mLayout.setMuted(true);
        mLayout.updateLayout();

        assertThat(mVolumeIcon.getDrawable().getLevel()).isEqualTo(0);
    }

    @Test
    public void updateLayout_unmuteAndExpanded_volumeIconIsCorrect() {
        mLayout.setMuted(false);
        mLayout.setExpanded(true);
        mLayout.updateLayout();

        int expectedLevel = calculateVolumeLevel(TEST_LEFT_VOLUME_LEVEL, TEST_RIGHT_VOLUME_LEVEL);
        assertThat(mVolumeIcon.getDrawable().getLevel()).isEqualTo(expectedLevel);
    }

    @Test
    public void updateLayout_unmuteAndNotExpanded_volumeIconIsCorrect() {
        mLayout.setMuted(false);
        mLayout.setExpanded(false);
        mLayout.updateLayout();

        int expectedLevel = calculateVolumeLevel(TEST_UNIFIED_VOLUME_LEVEL,
                TEST_UNIFIED_VOLUME_LEVEL);
        assertThat(mVolumeIcon.getDrawable().getLevel()).isEqualTo(expectedLevel);
    }

    @Test
    public void setSliderEnabled_expandedAndLeftIsDisabled_volumeIconIsCorrect() {
        mLayout.setExpanded(true);
        mLayout.setSliderEnabled(SIDE_LEFT, false);

        int expectedLevel = calculateVolumeLevel(0, TEST_RIGHT_VOLUME_LEVEL);
        assertThat(mVolumeIcon.getDrawable().getLevel()).isEqualTo(expectedLevel);
    }

    @Test
    public void setSliderValue_expandedAndLeftValueChanged_volumeIconIsCorrect() {
        mLayout.setExpanded(true);
        mLayout.setSliderValue(SIDE_LEFT, 4);

        int expectedLevel = calculateVolumeLevel(4, TEST_RIGHT_VOLUME_LEVEL);
        assertThat(mVolumeIcon.getDrawable().getLevel()).isEqualTo(expectedLevel);
    }

    private int calculateVolumeLevel(int left, int right) {
        return left * 5 + right;
    }

    private void assertControlUiCorrect() {
        final boolean expanded = mLayout.isExpanded();
        final Map<Integer, AmbientVolumeSlider> sliders = mLayout.getSliders();
        if (expanded) {
            assertThat(sliders.get(SIDE_UNIFIED).getVisibility()).isEqualTo(GONE);
            assertThat(sliders.get(SIDE_LEFT).getVisibility()).isEqualTo(VISIBLE);
            assertThat(sliders.get(SIDE_RIGHT).getVisibility()).isEqualTo(VISIBLE);
            assertThat(mExpandIcon.getRotation()).isEqualTo(ROTATION_EXPANDED);
        } else {
            assertThat(sliders.get(SIDE_UNIFIED).getVisibility()).isEqualTo(VISIBLE);
            assertThat(sliders.get(SIDE_LEFT).getVisibility()).isEqualTo(GONE);
            assertThat(sliders.get(SIDE_RIGHT).getVisibility()).isEqualTo(GONE);
            assertThat(mExpandIcon.getRotation()).isEqualTo(ROTATION_COLLAPSED);
        }
    }

    private void prepareDevices() {
        mSideToDeviceMap.put(SIDE_LEFT, mock(BluetoothDevice.class));
        mSideToDeviceMap.put(SIDE_RIGHT, mock(BluetoothDevice.class));
    }
}
