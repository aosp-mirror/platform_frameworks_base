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

package com.android.systemui.media.dialog;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.media.AudioManager;
import android.media.session.MediaSessionManager;
import android.os.PowerExemptionManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast;
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcastAssistant;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.settingslib.media.BluetoothMediaDevice;
import com.android.settingslib.media.LocalMediaManager;
import com.android.settingslib.media.MediaDevice;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.media.nearby.NearbyMediaDevicesManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;

import com.google.common.base.Strings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class MediaOutputBroadcastDialogTest extends SysuiTestCase {

    private static final String TEST_PACKAGE = "test_package";
    private static final String BROADCAST_NAME_TEST = "Broadcast_name_test";
    private static final String BROADCAST_CODE_TEST = "112233";
    private static final String BROADCAST_CODE_UPDATE_TEST = "11223344";

    // Mock
    private final MediaSessionManager mMediaSessionManager = mock(MediaSessionManager.class);
    private final LocalBluetoothManager mLocalBluetoothManager = mock(LocalBluetoothManager.class);
    private final LocalBluetoothProfileManager mLocalBluetoothProfileManager = mock(
            LocalBluetoothProfileManager.class);
    private final LocalBluetoothLeBroadcast mLocalBluetoothLeBroadcast = mock(
            LocalBluetoothLeBroadcast.class);
    private final LocalBluetoothLeBroadcastAssistant mLocalBluetoothLeBroadcastAssistant = mock(
            LocalBluetoothLeBroadcastAssistant.class);
    private final BluetoothLeBroadcastMetadata mBluetoothLeBroadcastMetadata = mock(
            BluetoothLeBroadcastMetadata.class);
    private final BluetoothLeBroadcastReceiveState mBluetoothLeBroadcastReceiveState = mock(
            BluetoothLeBroadcastReceiveState.class);
    private final ActivityStarter mStarter = mock(ActivityStarter.class);
    private final BroadcastSender mBroadcastSender = mock(BroadcastSender.class);
    private final LocalMediaManager mLocalMediaManager = mock(LocalMediaManager.class);
    private final MediaDevice mBluetoothMediaDevice = mock(BluetoothMediaDevice.class);
    private final BluetoothDevice mBluetoothFirstDevice = mock(BluetoothDevice.class);
    private final BluetoothDevice mBluetoothSecondDevice = mock(BluetoothDevice.class);
    private final CachedBluetoothDevice mCachedBluetoothDevice = mock(CachedBluetoothDevice.class);
    private final CommonNotifCollection mNotifCollection = mock(CommonNotifCollection.class);
    private final DialogTransitionAnimator mDialogTransitionAnimator = mock(
            DialogTransitionAnimator.class);
    private final NearbyMediaDevicesManager mNearbyMediaDevicesManager = mock(
            NearbyMediaDevicesManager.class);
    private final AudioManager mAudioManager = mock(AudioManager.class);
    private PowerExemptionManager mPowerExemptionManager = mock(PowerExemptionManager.class);
    private KeyguardManager mKeyguardManager = mock(KeyguardManager.class);
    private FeatureFlags mFlags = mock(FeatureFlags.class);
    private UserTracker mUserTracker = mock(UserTracker.class);

    private MediaOutputBroadcastDialog mMediaOutputBroadcastDialog;
    private MediaOutputController mMediaOutputController;

    @Before
    public void setUp() {
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mLocalBluetoothProfileManager);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(null);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(null);
        when(mLocalBluetoothLeBroadcast.getProgramInfo()).thenReturn(BROADCAST_NAME_TEST);
        when(mLocalBluetoothLeBroadcast.getBroadcastCode()).thenReturn(
                BROADCAST_CODE_TEST.getBytes(StandardCharsets.UTF_8));

        mMediaOutputController = new MediaOutputController(mContext, TEST_PACKAGE,
                mMediaSessionManager, mLocalBluetoothManager, mStarter,
                mNotifCollection, mDialogTransitionAnimator,
                mNearbyMediaDevicesManager, mAudioManager, mPowerExemptionManager,
                mKeyguardManager, mFlags, mUserTracker);
        mMediaOutputController.mLocalMediaManager = mLocalMediaManager;
        mMediaOutputBroadcastDialog = new MediaOutputBroadcastDialog(mContext, false,
                mBroadcastSender, mMediaOutputController);
        mMediaOutputBroadcastDialog.show();
    }

    @After
    public void tearDown() {
        if (mMediaOutputBroadcastDialog.mAlertDialog != null){
            mMediaOutputBroadcastDialog.mAlertDialog.dismiss();
        }
        mMediaOutputBroadcastDialog.dismiss();
    }

    @Test
    public void startBroadcastWithConnectedDevices_noBroadcastMetadata_failToAddSource() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.getLatestBluetoothLeBroadcastMetadata()).thenReturn(null);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(
                mLocalBluetoothLeBroadcastAssistant);

        mMediaOutputBroadcastDialog.startBroadcastWithConnectedDevices();

        verify(mLocalBluetoothLeBroadcastAssistant, never()).addSource(any(), any(), anyBoolean());
    }

    @Test
    public void startBroadcastWithConnectedDevices_noConnectedMediaDevice_failToAddSource() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.getLatestBluetoothLeBroadcastMetadata()).thenReturn(
                mBluetoothLeBroadcastMetadata);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(
                mLocalBluetoothLeBroadcastAssistant);
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(null);

        mMediaOutputBroadcastDialog.startBroadcastWithConnectedDevices();

        verify(mLocalBluetoothLeBroadcastAssistant, never()).addSource(any(), any(), anyBoolean());
    }

    @Test
    public void startBroadcastWithConnectedDevices_hasBroadcastSource_failToAddSource() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothLeBroadcast.getLatestBluetoothLeBroadcastMetadata()).thenReturn(
                mBluetoothLeBroadcastMetadata);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(
                mLocalBluetoothLeBroadcastAssistant);
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mBluetoothMediaDevice);
        when(((BluetoothMediaDevice) mBluetoothMediaDevice).getCachedDevice())
                .thenReturn(mCachedBluetoothDevice);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothFirstDevice);
        List<BluetoothLeBroadcastReceiveState> sourceList = new ArrayList<>();
        sourceList.add(mBluetoothLeBroadcastReceiveState);
        when(mLocalBluetoothLeBroadcastAssistant.getAllSources(mBluetoothFirstDevice)).thenReturn(
                sourceList);

        mMediaOutputBroadcastDialog.startBroadcastWithConnectedDevices();

        verify(mLocalBluetoothLeBroadcastAssistant, never()).addSource(any(), any(), anyBoolean());
    }

    @Test
    public void startBroadcastWithConnectedDevices_noBroadcastSource_failToAddSource() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(
                mLocalBluetoothLeBroadcastAssistant);
        when(mLocalBluetoothLeBroadcast.getLatestBluetoothLeBroadcastMetadata()).thenReturn(
                mBluetoothLeBroadcastMetadata);
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mBluetoothMediaDevice);
        when(mBluetoothMediaDevice.isBLEDevice()).thenReturn(true);
        when(((BluetoothMediaDevice) mBluetoothMediaDevice).getCachedDevice()).thenReturn(
                mCachedBluetoothDevice);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothFirstDevice);
        List<BluetoothLeBroadcastReceiveState> sourceList = new ArrayList<>();
        when(mLocalBluetoothLeBroadcastAssistant.getAllSources(mBluetoothFirstDevice)).thenReturn(
                sourceList);
        List<BluetoothDevice> connectedDevicesList = new ArrayList<>();
        connectedDevicesList.add(mBluetoothFirstDevice);
        when(mLocalBluetoothLeBroadcastAssistant.getConnectedDevices()).thenReturn(
                connectedDevicesList);

        mMediaOutputBroadcastDialog.startBroadcastWithConnectedDevices();

        verify(mLocalBluetoothLeBroadcastAssistant, times(1)).addSource(any(), any(), anyBoolean());
    }

    @Test
    public void handleLeBroadcastMetadataChanged_checkBroadcastName() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        final TextView broadcastName = mMediaOutputBroadcastDialog.mDialogView
                .requireViewById(R.id.broadcast_name_summary);

        mMediaOutputBroadcastDialog.handleLeBroadcastMetadataChanged();

        assertThat(broadcastName.getText().toString()).isEqualTo(BROADCAST_NAME_TEST);
    }

    @Test
    public void handleLeBroadcastMetadataChanged_checkBroadcastCode() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);

        final TextView broadcastCode = mMediaOutputBroadcastDialog.mDialogView
                .requireViewById(R.id.broadcast_code_summary);

        mMediaOutputBroadcastDialog.handleLeBroadcastMetadataChanged();

        assertThat(broadcastCode.getText().toString()).isEqualTo(BROADCAST_CODE_TEST);
    }

    @Test
    public void updateBroadcastInfo_stopBroadcastFailed_handleFailedUi() {
        ImageView broadcastCodeEdit = mMediaOutputBroadcastDialog.mDialogView
                .requireViewById(R.id.broadcast_code_edit);
        TextView broadcastCode = mMediaOutputBroadcastDialog.mDialogView.requireViewById(
                R.id.broadcast_code_summary);
        broadcastCode.setText(BROADCAST_CODE_UPDATE_TEST);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(null);
        broadcastCodeEdit.callOnClick();

        mMediaOutputBroadcastDialog.updateBroadcastInfo(true, BROADCAST_CODE_UPDATE_TEST);
        assertThat(mMediaOutputBroadcastDialog.getRetryCount()).isEqualTo(1);

        mMediaOutputBroadcastDialog.updateBroadcastInfo(true, BROADCAST_CODE_UPDATE_TEST);
        assertThat(mMediaOutputBroadcastDialog.getRetryCount()).isEqualTo(2);

        // It will be the MAX Retry Count = 3
        mMediaOutputBroadcastDialog.updateBroadcastInfo(true, BROADCAST_CODE_UPDATE_TEST);
        assertThat(mMediaOutputBroadcastDialog.getRetryCount()).isEqualTo(0);
    }

    @Test
    public void afterTextChanged_nameLengthMoreThanMax_showErrorMessage() {
        ImageView broadcastNameEdit = mMediaOutputBroadcastDialog.mDialogView
                .requireViewById(R.id.broadcast_name_edit);
        TextView broadcastName = mMediaOutputBroadcastDialog.mDialogView.requireViewById(
                R.id.broadcast_name_summary);
        broadcastName.setText(BROADCAST_NAME_TEST);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        broadcastNameEdit.callOnClick();
        EditText editText = mMediaOutputBroadcastDialog.mAlertDialog.findViewById(
                R.id.broadcast_edit_text);
        TextView broadcastErrorMessage = mMediaOutputBroadcastDialog.mAlertDialog.findViewById(
                R.id.broadcast_error_message);
        assertThat(broadcastErrorMessage.getVisibility()).isEqualTo(View.INVISIBLE);

        // input the invalid text
        String moreThanMax = Strings.repeat("a",
                MediaOutputBroadcastDialog.BROADCAST_NAME_MAX_LENGTH + 3);
        editText.setText(moreThanMax);

        assertThat(broadcastErrorMessage.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void afterTextChanged_enterValidNameAfterLengthMoreThanMax_noErrorMessage() {
        ImageView broadcastNameEdit = mMediaOutputBroadcastDialog.mDialogView
                .requireViewById(R.id.broadcast_name_edit);
        TextView broadcastName = mMediaOutputBroadcastDialog.mDialogView.requireViewById(
                R.id.broadcast_name_summary);
        broadcastName.setText(BROADCAST_NAME_TEST);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        broadcastNameEdit.callOnClick();
        EditText editText = mMediaOutputBroadcastDialog.mAlertDialog.findViewById(
                R.id.broadcast_edit_text);
        TextView broadcastErrorMessage = mMediaOutputBroadcastDialog.mAlertDialog.findViewById(
                R.id.broadcast_error_message);
        assertThat(broadcastErrorMessage.getVisibility()).isEqualTo(View.INVISIBLE);

        // input the invalid text
        String testString = Strings.repeat("a",
                MediaOutputBroadcastDialog.BROADCAST_NAME_MAX_LENGTH + 2);
        editText.setText(testString);
        assertThat(broadcastErrorMessage.getVisibility()).isEqualTo(View.VISIBLE);

        // input the valid text
        testString = Strings.repeat("b",
                MediaOutputBroadcastDialog.BROADCAST_NAME_MAX_LENGTH - 100);
        editText.setText(testString);

        assertThat(broadcastErrorMessage.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    @Test
    public void afterTextChanged_codeLengthMoreThanMax_showErrorMessage() {
        ImageView broadcastCodeEdit = mMediaOutputBroadcastDialog.mDialogView
                .requireViewById(R.id.broadcast_code_edit);
        TextView broadcastCode = mMediaOutputBroadcastDialog.mDialogView.requireViewById(
                R.id.broadcast_code_summary);
        broadcastCode.setText(BROADCAST_CODE_UPDATE_TEST);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        broadcastCodeEdit.callOnClick();
        EditText editText = mMediaOutputBroadcastDialog.mAlertDialog.findViewById(
                R.id.broadcast_edit_text);
        TextView broadcastErrorMessage = mMediaOutputBroadcastDialog.mAlertDialog.findViewById(
                R.id.broadcast_error_message);
        assertThat(broadcastErrorMessage.getVisibility()).isEqualTo(View.INVISIBLE);

        // input the invalid text
        String moreThanMax = Strings.repeat("a",
                MediaOutputBroadcastDialog.BROADCAST_CODE_MAX_LENGTH + 1);
        editText.setText(moreThanMax);

        assertThat(broadcastErrorMessage.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void afterTextChanged_codeLengthLessThanMin_showErrorMessage() {
        ImageView broadcastCodeEdit = mMediaOutputBroadcastDialog.mDialogView
                .requireViewById(R.id.broadcast_code_edit);
        TextView broadcastCode = mMediaOutputBroadcastDialog.mDialogView.requireViewById(
                R.id.broadcast_code_summary);
        broadcastCode.setText(BROADCAST_CODE_UPDATE_TEST);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        broadcastCodeEdit.callOnClick();
        EditText editText = mMediaOutputBroadcastDialog.mAlertDialog.findViewById(
                R.id.broadcast_edit_text);
        TextView broadcastErrorMessage = mMediaOutputBroadcastDialog.mAlertDialog.findViewById(
                R.id.broadcast_error_message);
        assertThat(broadcastErrorMessage.getVisibility()).isEqualTo(View.INVISIBLE);

        // input the invalid text
        String moreThanMax = Strings.repeat("a",
                MediaOutputBroadcastDialog.BROADCAST_CODE_MIN_LENGTH - 1);
        editText.setText(moreThanMax);

        assertThat(broadcastErrorMessage.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void addSourceToAllConnectedDevices() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(
                mLocalBluetoothLeBroadcastAssistant);
        when(mLocalBluetoothLeBroadcast.getLatestBluetoothLeBroadcastMetadata()).thenReturn(
                mBluetoothLeBroadcastMetadata);
        when(mLocalMediaManager.getCurrentConnectedDevice()).thenReturn(mBluetoothMediaDevice);
        when(mBluetoothMediaDevice.isBLEDevice()).thenReturn(true);
        when(((BluetoothMediaDevice) mBluetoothMediaDevice).getCachedDevice())
                .thenReturn(mCachedBluetoothDevice);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothFirstDevice);
        List<BluetoothLeBroadcastReceiveState> sourceList = new ArrayList<>();
        when(mLocalBluetoothLeBroadcastAssistant.getAllSources(mBluetoothFirstDevice)).thenReturn(
                sourceList);
        List<BluetoothDevice> connectedDevicesList = new ArrayList<>();
        connectedDevicesList.add(mBluetoothFirstDevice);
        connectedDevicesList.add(mBluetoothSecondDevice);
        when(mLocalBluetoothLeBroadcastAssistant.getConnectedDevices()).thenReturn(
                connectedDevicesList);

        mMediaOutputBroadcastDialog.startBroadcastWithConnectedDevices();

        verify(mLocalBluetoothLeBroadcastAssistant, times(2)).addSource(any(), any(), anyBoolean());
    }

}
