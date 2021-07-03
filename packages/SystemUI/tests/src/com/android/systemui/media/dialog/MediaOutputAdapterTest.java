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

import com.android.settingslib.media.LocalMediaManager;
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
public class MediaOutputAdapterTest extends SysuiTestCase {

    private static final String TEST_DEVICE_NAME_1 = "test_device_name_1";
    private static final String TEST_DEVICE_NAME_2 = "test_device_name_2";
    private static final String TEST_DEVICE_ID_1 = "test_device_id_1";
    private static final String TEST_DEVICE_ID_2 = "test_device_id_2";
    private static final String TEST_SESSION_NAME = "test_session_name";

    // Mock
    private MediaOutputController mMediaOutputController = mock(MediaOutputController.class);
    private MediaDevice mMediaDevice1 = mock(MediaDevice.class);
    private MediaDevice mMediaDevice2 = mock(MediaDevice.class);
    private Icon mIcon = mock(Icon.class);
    private IconCompat mIconCompat = mock(IconCompat.class);

    private MediaOutputAdapter mMediaOutputAdapter;
    private MediaOutputAdapter.MediaDeviceViewHolder mViewHolder;
    private List<MediaDevice> mMediaDevices = new ArrayList<>();

    @Before
    public void setUp() {
        mMediaOutputAdapter = new MediaOutputAdapter(mMediaOutputController);
        mViewHolder = (MediaOutputAdapter.MediaDeviceViewHolder) mMediaOutputAdapter
                .onCreateViewHolder(new LinearLayout(mContext), 0);

        when(mMediaOutputController.getMediaDevices()).thenReturn(mMediaDevices);
        when(mMediaOutputController.hasAdjustVolumeUserRestriction()).thenReturn(false);
        when(mMediaOutputController.isZeroMode()).thenReturn(false);
        when(mMediaOutputController.isTransferring()).thenReturn(false);
        when(mMediaOutputController.getDeviceIconCompat(mMediaDevice1)).thenReturn(mIconCompat);
        when(mMediaOutputController.getDeviceIconCompat(mMediaDevice2)).thenReturn(mIconCompat);
        when(mMediaOutputController.getCurrentConnectedMediaDevice()).thenReturn(mMediaDevice1);
        when(mMediaOutputController.isActiveRemoteDevice(mMediaDevice1)).thenReturn(true);
        when(mIconCompat.toIcon(mContext)).thenReturn(mIcon);
        when(mMediaDevice1.getName()).thenReturn(TEST_DEVICE_NAME_1);
        when(mMediaDevice1.getId()).thenReturn(TEST_DEVICE_ID_1);
        when(mMediaDevice2.getName()).thenReturn(TEST_DEVICE_NAME_2);
        when(mMediaDevice2.getId()).thenReturn(TEST_DEVICE_ID_2);
        when(mMediaDevice1.getState()).thenReturn(
                LocalMediaManager.MediaDeviceState.STATE_CONNECTED);
        when(mMediaDevice2.getState()).thenReturn(
                LocalMediaManager.MediaDeviceState.STATE_DISCONNECTED);
        mMediaDevices.add(mMediaDevice1);
        mMediaDevices.add(mMediaDevice2);
    }

    @Test
    public void getItemCount_nonZeroMode_isDeviceSize() {
        assertThat(mMediaOutputAdapter.getItemCount()).isEqualTo(mMediaDevices.size());
    }

    @Test
    public void getItemCount_zeroMode_containExtraOneForPairNew() {
        when(mMediaOutputController.isZeroMode()).thenReturn(true);

        assertThat(mMediaOutputAdapter.getItemCount()).isEqualTo(mMediaDevices.size() + 1);
    }

    @Test
    public void getItemCount_withDynamicGroup_containExtraOneForGroup() {
        when(mMediaOutputController.getSelectedMediaDevice()).thenReturn(mMediaDevices);
        when(mMediaOutputController.isZeroMode()).thenReturn(false);

        assertThat(mMediaOutputAdapter.getItemCount()).isEqualTo(mMediaDevices.size() + 1);
    }

    @Test
    public void onBindViewHolder_zeroMode_bindPairNew_verifyView() {
        when(mMediaOutputController.isZeroMode()).thenReturn(true);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 2);

        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mAddIcon.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mBottomDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getText()).isEqualTo(mContext.getText(
                R.string.media_output_dialog_pairing_new));
    }

    @Test
    public void onBindViewHolder_bindGroup_withSessionName_verifyView() {
        when(mMediaOutputController.getSelectedMediaDevice()).thenReturn(mMediaDevices);
        when(mMediaOutputController.isZeroMode()).thenReturn(false);
        when(mMediaOutputController.getSessionName()).thenReturn(TEST_SESSION_NAME);
        mMediaOutputAdapter.getItemCount();
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mBottomDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTwoLineTitleText.getText()).isEqualTo(TEST_SESSION_NAME);
    }

    @Test
    public void onBindViewHolder_bindGroup_noSessionName_verifyView() {
        when(mMediaOutputController.getSelectedMediaDevice()).thenReturn(mMediaDevices);
        when(mMediaOutputController.isZeroMode()).thenReturn(false);
        when(mMediaOutputController.getSessionName()).thenReturn(null);
        mMediaOutputAdapter.getItemCount();
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mBottomDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTwoLineTitleText.getText()).isEqualTo(mContext.getString(
                R.string.media_output_dialog_group));
    }

    @Test
    public void onBindViewHolder_bindConnectedDevice_verifyView() {
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mBottomDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTwoLineTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineTitleText.getText()).isEqualTo(TEST_DEVICE_NAME_1);
    }

    @Test
    public void onBindViewHolder_bindConnectedDevice_withSelectableDevice_showAddIcon() {
        when(mMediaOutputController.getSelectableMediaDevice()).thenReturn(mMediaDevices);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mDivider.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mAddIcon.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void onBindViewHolder_bindConnectedDevice_withoutSelectableDevice_hideAddIcon() {
        when(mMediaOutputController.getSelectableMediaDevice()).thenReturn(new ArrayList<>());
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mAddIcon.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void onBindViewHolder_bindNonActiveConnectedDevice_verifyView() {
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 1);

        assertThat(mViewHolder.mDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mAddIcon.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mBottomDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText().toString()).isEqualTo(TEST_DEVICE_NAME_2);
    }

    @Test
    public void onBindViewHolder_bindDisconnectedBluetoothDevice_verifyView() {
        when(mMediaDevice2.getDeviceType()).thenReturn(
                MediaDevice.MediaDeviceType.TYPE_BLUETOOTH_DEVICE);
        when(mMediaDevice2.isConnected()).thenReturn(false);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 1);

        assertThat(mViewHolder.mDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mAddIcon.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mBottomDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText().toString()).isEqualTo(
                mContext.getString(R.string.media_output_dialog_disconnected, TEST_DEVICE_NAME_2));
    }

    @Test
    public void onBindViewHolder_bindFailedStateDevice_verifyView() {
        when(mMediaDevice2.getState()).thenReturn(
                LocalMediaManager.MediaDeviceState.STATE_CONNECTING_FAILED);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 1);

        assertThat(mViewHolder.mDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mAddIcon.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mBottomDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mSubTitleText.getText()).isEqualTo(mContext.getText(
                R.string.media_output_dialog_connect_failed));
        assertThat(mViewHolder.mTwoLineTitleText.getText()).isEqualTo(TEST_DEVICE_NAME_2);
    }

    @Test
    public void onBindViewHolder_inTransferring_bindTransferringDevice_verifyView() {
        when(mMediaOutputController.isTransferring()).thenReturn(true);
        when(mMediaDevice1.getState()).thenReturn(
                LocalMediaManager.MediaDeviceState.STATE_CONNECTING);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSeekBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mSubTitleText.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mBottomDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTwoLineTitleText.getText()).isEqualTo(TEST_DEVICE_NAME_1);
    }

    @Test
    public void onBindViewHolder_inTransferring_bindNonTransferringDevice_verifyView() {
        when(mMediaOutputController.isTransferring()).thenReturn(true);
        when(mMediaDevice2.getState()).thenReturn(
                LocalMediaManager.MediaDeviceState.STATE_CONNECTING);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mTwoLineLayout.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mProgressBar.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mCheckBox.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mBottomDivider.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewHolder.mTitleText.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mTitleText.getText()).isEqualTo(TEST_DEVICE_NAME_1);
    }

    @Test
    public void onItemClick_clickPairNew_verifyLaunchBluetoothPairing() {
        when(mMediaOutputController.isZeroMode()).thenReturn(true);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 2);
        mViewHolder.mContainerLayout.performClick();

        verify(mMediaOutputController).launchBluetoothPairing();
    }

    @Test
    public void onItemClick_clickDevice_verifyConnectDevice() {
        assertThat(mMediaDevice2.getState()).isEqualTo(
                LocalMediaManager.MediaDeviceState.STATE_DISCONNECTED);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 0);
        mMediaOutputAdapter.onBindViewHolder(mViewHolder, 1);
        mViewHolder.mContainerLayout.performClick();

        verify(mMediaOutputController).connectDevice(mMediaDevice2);
    }
}
