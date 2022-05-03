/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.dialog;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.drawable.Icon;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.widget.LinearLayout;

import androidx.core.graphics.drawable.IconCompat;
import androidx.test.filters.SmallTest;

import com.android.settingslib.media.MediaDevice;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class MediaOutputGroupAdapterTest extends SysuiTestCase {

    private static final String TEST_DEVICE_NAME_1 = "test_device_name_1";
    private static final String TEST_DEVICE_NAME_2 = "test_device_name_2";
    private static final String TEST_DEVICE_ID_1 = "test_device_id_1";
    private static final String TEST_DEVICE_ID_2 = "test_device_id_2";
    private static final int TEST_VOLUME = 10;
    private static final int TEST_MAX_VOLUME = 50;

    // Mock
    private MediaOutputController mMediaOutputController = mock(MediaOutputController.class);
    private MediaDevice mMediaDevice1 = mock(MediaDevice.class);
    private MediaDevice mMediaDevice2 = mock(MediaDevice.class);
    private Icon mIcon = mock(Icon.class);
    private IconCompat mIconCompat = mock(IconCompat.class);

    private MediaOutputGroupAdapter mGroupAdapter;
    private MediaOutputGroupAdapter.GroupViewHolder mGroupViewHolder;
    private List<MediaDevice> mGroupMediaDevices = new ArrayList<>();
    private List<MediaDevice> mSelectableMediaDevices = new ArrayList<>();
    private List<MediaDevice> mSelectedMediaDevices = new ArrayList<>();
    private List<MediaDevice> mDeselectableMediaDevices = new ArrayList<>();

    @Before
    public void setUp() {
        when(mMediaOutputController.getGroupMediaDevices()).thenReturn(mGroupMediaDevices);
        when(mMediaOutputController.getDeviceIconCompat(mMediaDevice1)).thenReturn(mIconCompat);
        when(mMediaOutputController.getDeviceIconCompat(mMediaDevice2)).thenReturn(mIconCompat);
        when(mMediaOutputController.getSelectableMediaDevice()).thenReturn(mSelectableMediaDevices);
        when(mMediaOutputController.getSelectedMediaDevice()).thenReturn(mSelectedMediaDevices);
        when(mMediaOutputController.getDeselectableMediaDevice()).thenReturn(
                mDeselectableMediaDevices);
        when(mIconCompat.toIcon(mContext)).thenReturn(mIcon);
        when(mMediaDevice1.getName()).thenReturn(TEST_DEVICE_NAME_1);
        when(mMediaDevice1.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(mMediaDevice2.getName()).thenReturn(TEST_DEVICE_NAME_2);
        when(mMediaDevice2.getId()).thenReturn(TEST_DEVICE_ID_2);
        mGroupMediaDevices.add(mMediaDevice1);
        mGroupMediaDevices.add(mMediaDevice2);
        mSelectedMediaDevices.add(mMediaDevice1);
        mSelectableMediaDevices.add(mMediaDevice2);
        mDeselectableMediaDevices.add(mMediaDevice1);

        mGroupAdapter = new MediaOutputGroupAdapter(mMediaOutputController);
        mGroupViewHolder = (MediaOutputGroupAdapter.GroupViewHolder) mGroupAdapter
                .onCreateViewHolder(new LinearLayout(mContext), 0);
    }

    @Test
    public void onBindViewHolder_verifyGroupItem() {
        mGroupAdapter.onBindViewHolder(mGroupViewHolder, 0);

        assertThat(mGroupViewHolder.mTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mGroupViewHolder.mTwoLineTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mGroupViewHolder.mTwoLineTitleText.getText()).isEqualTo(mContext.getText(
                R.string.media_output_dialog_group));
    }

    @Test
    public void onBindViewHolder_singleSelectedDevice_verifyView() {
        mGroupAdapter.onBindViewHolder(mGroupViewHolder, 1);

        assertThat(mGroupViewHolder.mTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mGroupViewHolder.mTwoLineTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mGroupViewHolder.mTwoLineTitleText.getText()).isEqualTo(TEST_DEVICE_NAME_1);
        assertThat(mGroupViewHolder.mCheckBox.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mGroupViewHolder.mCheckBox.isChecked()).isTrue();
        // Disabled checkBox
        assertThat(mGroupViewHolder.mCheckBox.isEnabled()).isFalse();
    }

    @Test
    public void onBindViewHolder_multipleSelectedDevice_verifyView() {
        mSelectedMediaDevices.clear();
        mSelectedMediaDevices.add(mMediaDevice1);
        mSelectedMediaDevices.add(mMediaDevice2);
        mDeselectableMediaDevices.clear();
        mDeselectableMediaDevices.add(mMediaDevice1);
        mDeselectableMediaDevices.add(mMediaDevice2);
        mSelectableMediaDevices.clear();

        mGroupAdapter.onBindViewHolder(mGroupViewHolder, 1);

        assertThat(mGroupViewHolder.mTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mGroupViewHolder.mTwoLineTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mGroupViewHolder.mTwoLineTitleText.getText()).isEqualTo(TEST_DEVICE_NAME_1);
        assertThat(mGroupViewHolder.mCheckBox.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mGroupViewHolder.mCheckBox.isChecked()).isTrue();
        // Enabled checkBox
        assertThat(mGroupViewHolder.mCheckBox.isEnabled()).isTrue();
    }

    @Test
    public void onBindViewHolder_notDeselectedDevice_verifyView() {
        mSelectedMediaDevices.clear();
        mSelectedMediaDevices.add(mMediaDevice1);
        mSelectedMediaDevices.add(mMediaDevice2);
        mDeselectableMediaDevices.clear();
        mDeselectableMediaDevices.add(mMediaDevice1);
        mSelectableMediaDevices.clear();

        mGroupAdapter.onBindViewHolder(mGroupViewHolder, 2);

        assertThat(mGroupViewHolder.mTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mGroupViewHolder.mTwoLineTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mGroupViewHolder.mTwoLineTitleText.getText()).isEqualTo(TEST_DEVICE_NAME_2);
        assertThat(mGroupViewHolder.mCheckBox.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mGroupViewHolder.mCheckBox.isChecked()).isTrue();
        // Disabled checkBox
        assertThat(mGroupViewHolder.mCheckBox.isEnabled()).isFalse();
    }

    @Test
    public void onBindViewHolder_selectableDevice_verifyCheckBox() {
        mGroupAdapter.onBindViewHolder(mGroupViewHolder, 2);

        assertThat(mGroupViewHolder.mTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mGroupViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mGroupViewHolder.mTwoLineTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mGroupViewHolder.mTwoLineTitleText.getText()).isEqualTo(TEST_DEVICE_NAME_2);
        assertThat(mGroupViewHolder.mCheckBox.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mGroupViewHolder.mCheckBox.isChecked()).isFalse();
        // Enabled checkBox
        assertThat(mGroupViewHolder.mCheckBox.isEnabled()).isTrue();
    }

    @Test
    public void onBindViewHolder_verifyDeviceVolume() {
        when(mMediaDevice1.getCurrentVolume()).thenReturn(TEST_VOLUME);
        when(mMediaDevice1.getMaxVolume()).thenReturn(TEST_MAX_VOLUME);
        mGroupViewHolder.mSeekBar.setVisibility(View.VISIBLE);

        mGroupAdapter.onBindViewHolder(mGroupViewHolder, 1);

        assertThat(mGroupViewHolder.mSeekBar.getVolume()).isEqualTo(TEST_VOLUME);
    }

    @Test
    public void clickSelectedDevice_verifyRemoveDeviceFromPlayMedia() {
        mSelectedMediaDevices.clear();
        mSelectedMediaDevices.add(mMediaDevice1);
        mSelectedMediaDevices.add(mMediaDevice2);
        mDeselectableMediaDevices.clear();
        mDeselectableMediaDevices.add(mMediaDevice1);
        mDeselectableMediaDevices.add(mMediaDevice2);
        mSelectableMediaDevices.clear();

        mGroupAdapter.onBindViewHolder(mGroupViewHolder, 1);
        mGroupViewHolder.mCheckBox.performClick();

        verify(mMediaOutputController).removeDeviceFromPlayMedia(mMediaDevice1);
    }

    @Test
    public void clickSelectabelDevice_verifyAddDeviceToPlayMedia() {
        mGroupAdapter.onBindViewHolder(mGroupViewHolder, 2);

        mGroupViewHolder.mCheckBox.performClick();

        verify(mMediaOutputController).addDeviceToPlayMedia(mMediaDevice2);
    }
}
