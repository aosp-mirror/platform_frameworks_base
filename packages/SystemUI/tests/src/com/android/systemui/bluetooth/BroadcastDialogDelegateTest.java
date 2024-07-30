/**
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.bluetooth;

import static com.android.systemui.statusbar.phone.SystemUIDialog.DEFAULT_DISMISS_ON_DEVICE_LOCK;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.TestableLooper;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.media.dialog.MediaOutputDialogManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BroadcastDialogDelegateTest extends SysuiTestCase {

    private static final String CURRENT_BROADCAST_APP = "Music";
    private static final String SWITCH_APP = "System UI";
    private static final String TEST_PACKAGE = "com.android.systemui";
    private final LocalBluetoothManager mLocalBluetoothManager = mock(LocalBluetoothManager.class);
    private final LocalBluetoothProfileManager mLocalBluetoothProfileManager = mock(
            LocalBluetoothProfileManager.class);
    private final LocalBluetoothLeBroadcast mLocalBluetoothLeBroadcast = mock(
            LocalBluetoothLeBroadcast.class);
    private final BroadcastSender mBroadcastSender = mock(BroadcastSender.class);
    private BroadcastDialogDelegate mBroadcastDialogDelegate;
    @Mock SystemUIDialog.Factory mSystemUIDialogFactory;
    @Mock SystemUIDialogManager mDialogManager;
    @Mock SysUiState mSysUiState;
    @Mock
    DialogTransitionAnimator mDialogTransitionAnimator;
    @Mock
    MediaOutputDialogManager mMediaOutputDialogManager;
    private SystemUIDialog mDialog;
    private TextView mTitle;
    private TextView mSubTitle;
    private Button mSwitchBroadcastAppButton;
    private Button mChangeOutputButton;
    private final FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mLocalBluetoothProfileManager);
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(null);

        when(mSysUiState.setFlag(anyLong(), anyBoolean())).thenReturn(mSysUiState);
        when(mSystemUIDialogFactory.create(any(), any())).thenReturn(mDialog);

        mBroadcastDialogDelegate = new BroadcastDialogDelegate(
                mContext,
                mMediaOutputDialogManager,
                mLocalBluetoothManager,
                new UiEventLoggerFake(),
                mFakeExecutor,
                mBroadcastSender,
                mSystemUIDialogFactory,
                CURRENT_BROADCAST_APP,
                TEST_PACKAGE);

        mDialog = new SystemUIDialog(
                mContext,
                0,
                DEFAULT_DISMISS_ON_DEVICE_LOCK,
                mDialogManager,
                mSysUiState,
                getFakeBroadcastDispatcher(),
                mDialogTransitionAnimator,
                mBroadcastDialogDelegate
        );

        mDialog.show();
    }

    @After
    public void tearDown() {
        mDialog.dismiss();
    }

    @Test
    public void onCreate_withCurrentApp_titleIsCurrentAppName() {
        mTitle = mDialog.requireViewById(R.id.dialog_title);

        assertThat(mTitle.getText().toString()).isEqualTo(mContext.getString(
                R.string.bt_le_audio_broadcast_dialog_title, CURRENT_BROADCAST_APP));
    }

    @Test
    public void onCreate_withCurrentApp_subTitleIsSwitchAppName() {
        mSubTitle = mDialog.requireViewById(R.id.dialog_subtitle);

        assertThat(mSubTitle.getText()).isEqualTo(
                mContext.getString(R.string.bt_le_audio_broadcast_dialog_sub_title, SWITCH_APP));
    }

    @Test
    public void onCreate_withCurrentApp_switchBtnIsSwitchAppName() {
        mSwitchBroadcastAppButton = mDialog.requireViewById(R.id.switch_broadcast);

        assertThat(mSwitchBroadcastAppButton.getText().toString()).isEqualTo(
                mContext.getString(R.string.bt_le_audio_broadcast_dialog_switch_app, SWITCH_APP));
    }

    @Test
    public void onClick_withChangeOutput_dismissBroadcastDialog() {
        mChangeOutputButton = mDialog.requireViewById(R.id.change_output);
        mChangeOutputButton.performClick();

        assertThat(mDialog.isShowing()).isFalse();
    }

    @Test
    public void onClick_withSwitchBroadcast_stopCurrentBroadcast() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);
        mSwitchBroadcastAppButton = mDialog.requireViewById(R.id.switch_broadcast);
        mSwitchBroadcastAppButton.performClick();

        verify(mLocalBluetoothLeBroadcast).stopLatestBroadcast();
    }

    @Test
    public void onClick_withSwitchBroadcast_stopCurrentBroadcastFailed() {
        mSwitchBroadcastAppButton = mDialog.requireViewById(R.id.switch_broadcast);
        mSwitchBroadcastAppButton.performClick();

        assertThat(mSwitchBroadcastAppButton.getText().toString()).isEqualTo(
                mContext.getString(R.string.bt_le_audio_broadcast_dialog_switch_app, SWITCH_APP));
    }

    @Test
    public void handleLeBroadcastStopped_withBroadcastProfileNull_doRefreshButton() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(null);
        mSwitchBroadcastAppButton = mDialog.requireViewById(R.id.switch_broadcast);

        mBroadcastDialogDelegate.handleLeBroadcastStopped();

        assertThat(mSwitchBroadcastAppButton.getText().toString()).isEqualTo(
                mContext.getString(R.string.bt_le_audio_broadcast_dialog_switch_app, SWITCH_APP));
    }

    @Test
    public void handleLeBroadcastStopped_withBroadcastProfile_doStartBroadcast() {
        when(mLocalBluetoothProfileManager.getLeAudioBroadcastProfile()).thenReturn(
                mLocalBluetoothLeBroadcast);

        mBroadcastDialogDelegate.handleLeBroadcastStopped();

        verify(mLocalBluetoothLeBroadcast).startBroadcast(eq(SWITCH_APP), any());
    }

    @Test
    public void handleLeBroadcastMetadataChanged_withNotLaunchFlag_doNotDismissDialog() {

        mBroadcastDialogDelegate.handleLeBroadcastMetadataChanged();

        assertThat(mDialog.isShowing()).isTrue();
    }

    @Test
    public void handleLeBroadcastMetadataChanged_withLaunchFlag_dismissDialog() {

        mBroadcastDialogDelegate.handleLeBroadcastStarted();
        mBroadcastDialogDelegate.handleLeBroadcastMetadataChanged();

        assertThat(mDialog.isShowing()).isFalse();
    }
}
