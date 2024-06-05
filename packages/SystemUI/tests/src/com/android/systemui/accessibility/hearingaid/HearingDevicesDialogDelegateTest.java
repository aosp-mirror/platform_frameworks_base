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

import static com.android.systemui.accessibility.hearingaid.HearingDevicesDialogDelegate.LIVE_CAPTION_INTENT;
import static com.android.systemui.statusbar.phone.SystemUIDialog.DEFAULT_DISMISS_ON_DEVICE_LOCK;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Handler;
import android.platform.test.annotations.EnableFlags;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.LinearLayout;

import androidx.test.filters.SmallTest;

import com.android.settingslib.bluetooth.BluetoothEventManager;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.HapClientProfile;
import com.android.settingslib.bluetooth.LocalBluetoothAdapter;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.bluetooth.qsdialog.DeviceItem;
import com.android.systemui.bluetooth.qsdialog.DeviceItemType;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

/** Tests for {@link HearingDevicesDialogDelegate}. */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class HearingDevicesDialogDelegateTest extends SysuiTestCase {
    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    private static final String DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF";
    private static final String TEST_PKG = "pkg";
    private static final String TEST_CLS = "cls";
    private static final ComponentName TEST_COMPONENT = new ComponentName(TEST_PKG, TEST_CLS);
    private static final String TEST_LABEL = "label";

    @Mock
    private SystemUIDialog.Factory mSystemUIDialogFactory;
    @Mock
    private SystemUIDialogManager mSystemUIDialogManager;
    @Mock
    private SysUiState mSysUiState;
    @Mock
    private DialogTransitionAnimator mDialogTransitionAnimator;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private LocalBluetoothAdapter mLocalBluetoothAdapter;
    @Mock
    private LocalBluetoothProfileManager mProfileManager;
    @Mock
    private HapClientProfile mHapClientProfile;
    @Mock
    private CachedBluetoothDeviceManager mCachedDeviceManager;
    @Mock
    private BluetoothEventManager mBluetoothEventManager;
    @Mock
    private AudioManager mAudioManager;
    @Mock
    private CachedBluetoothDevice mCachedDevice;
    @Mock
    private DeviceItem mHearingDeviceItem;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private ActivityInfo mActivityInfo;
    @Mock
    private Drawable mDrawable;
    private SystemUIDialog mDialog;
    private HearingDevicesDialogDelegate mDialogDelegate;
    private TestableLooper mTestableLooper;
    private final List<CachedBluetoothDevice> mDevices = new ArrayList<>();

    @Before
    public void setUp() {
        mTestableLooper = TestableLooper.get(this);
        when(mLocalBluetoothManager.getBluetoothAdapter()).thenReturn(mLocalBluetoothAdapter);
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mProfileManager);
        when(mProfileManager.getHapClientProfile()).thenReturn(mHapClientProfile);
        when(mLocalBluetoothAdapter.isEnabled()).thenReturn(true);
        when(mLocalBluetoothManager.getCachedDeviceManager()).thenReturn(mCachedDeviceManager);
        when(mCachedDeviceManager.getCachedDevicesCopy()).thenReturn(mDevices);
        when(mLocalBluetoothManager.getEventManager()).thenReturn(mBluetoothEventManager);
        when(mSysUiState.setFlag(anyLong(), anyBoolean())).thenReturn(mSysUiState);
        when(mCachedDevice.getAddress()).thenReturn(DEVICE_ADDRESS);
        when(mHearingDeviceItem.getCachedBluetoothDevice()).thenReturn(mCachedDevice);
        mContext.setMockPackageManager(mPackageManager);

        setUpPairNewDeviceDialog();

        when(mSystemUIDialogFactory.create(any(SystemUIDialog.Delegate.class)))
                .thenReturn(mDialog);
    }

    @Test
    public void createDialog_dialogShown() {
        assertThat(mDialogDelegate.createDialog()).isEqualTo(mDialog);
    }

    @Test
    public void clickPairNewDeviceButton_intentActionMatch() {
        mDialog.show();

        getPairNewDeviceButton(mDialog).performClick();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mActivityStarter).postStartActivityDismissingKeyguard(intentCaptor.capture(),
                anyInt(), any());
        assertThat(intentCaptor.getValue().getAction()).isEqualTo(
                Settings.ACTION_HEARING_DEVICE_PAIRING_SETTINGS);
    }

    @Test
    public void onDeviceItemGearClicked_intentActionMatch() {
        setUpDeviceListDialog();

        mDialogDelegate.onDeviceItemGearClicked(mHearingDeviceItem, new View(mContext));

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mActivityStarter).postStartActivityDismissingKeyguard(intentCaptor.capture(),
                anyInt(), any());
        assertThat(intentCaptor.getValue().getAction()).isEqualTo(
                HearingDevicesDialogDelegate.ACTION_BLUETOOTH_DEVICE_DETAILS);

    }

    @Test
    public void onDeviceItemOnClicked_connectedDevice_disconnect() {
        when(mHearingDeviceItem.getType()).thenReturn(DeviceItemType.CONNECTED_BLUETOOTH_DEVICE);

        mDialogDelegate.onDeviceItemOnClicked(mHearingDeviceItem, new View(mContext));

        verify(mCachedDevice).disconnect();
    }

    @Test
    @EnableFlags(Flags.FLAG_HEARING_DEVICES_DIALOG_RELATED_TOOLS)
    public void showDialog_hasLiveCaption_noRelatedToolsInConfig_showOneRelatedTool() {
        when(mPackageManager.queryIntentActivities(
                eq(LIVE_CAPTION_INTENT), anyInt())).thenReturn(
                List.of(new ResolveInfo()));
        mContext.getOrCreateTestableResources().addOverride(
                R.array.config_quickSettingsHearingDevicesRelatedToolName, new String[]{});

        setUpPairNewDeviceDialog();
        mDialog.show();

        LinearLayout relatedToolsView = (LinearLayout) getRelatedToolsView(mDialog);
        assertThat(relatedToolsView.getChildCount()).isEqualTo(1);
    }

    @Test
    @EnableFlags(Flags.FLAG_HEARING_DEVICES_DIALOG_RELATED_TOOLS)
    public void showDialog_hasLiveCaption_oneRelatedToolInConfig_showTwoRelatedTools()
            throws PackageManager.NameNotFoundException {
        when(mPackageManager.queryIntentActivities(
                eq(LIVE_CAPTION_INTENT), anyInt())).thenReturn(
                List.of(new ResolveInfo()));
        mContext.getOrCreateTestableResources().addOverride(
                R.array.config_quickSettingsHearingDevicesRelatedToolName,
                new String[]{TEST_PKG + "/" + TEST_CLS});
        when(mPackageManager.getActivityInfo(eq(TEST_COMPONENT), anyInt())).thenReturn(
                mActivityInfo);
        when(mActivityInfo.loadLabel(mPackageManager)).thenReturn(TEST_LABEL);
        when(mActivityInfo.loadIcon(mPackageManager)).thenReturn(mDrawable);
        when(mActivityInfo.getComponentName()).thenReturn(TEST_COMPONENT);

        setUpPairNewDeviceDialog();
        mDialog.show();

        LinearLayout relatedToolsView = (LinearLayout) getRelatedToolsView(mDialog);
        assertThat(relatedToolsView.getChildCount()).isEqualTo(2);
    }

    private void setUpPairNewDeviceDialog() {
        mDialogDelegate = new HearingDevicesDialogDelegate(
                mContext,
                true,
                mSystemUIDialogFactory,
                mActivityStarter,
                mDialogTransitionAnimator,
                mLocalBluetoothManager,
                new Handler(mTestableLooper.getLooper()),
                mAudioManager
        );
        mDialog = new SystemUIDialog(
                mContext,
                0,
                DEFAULT_DISMISS_ON_DEVICE_LOCK,
                mSystemUIDialogManager,
                mSysUiState,
                getFakeBroadcastDispatcher(),
                mDialogTransitionAnimator,
                mDialogDelegate
        );
    }

    private void setUpDeviceListDialog() {
        mDialogDelegate = new HearingDevicesDialogDelegate(
                mContext,
                false,
                mSystemUIDialogFactory,
                mActivityStarter,
                mDialogTransitionAnimator,
                mLocalBluetoothManager,
                new Handler(mTestableLooper.getLooper()),
                mAudioManager
        );
        mDialog = new SystemUIDialog(
                mContext,
                0,
                DEFAULT_DISMISS_ON_DEVICE_LOCK,
                mSystemUIDialogManager,
                mSysUiState,
                getFakeBroadcastDispatcher(),
                mDialogTransitionAnimator,
                mDialogDelegate
        );
    }

    private View getPairNewDeviceButton(SystemUIDialog dialog) {
        return dialog.requireViewById(R.id.pair_new_device_button);
    }

    private View getRelatedToolsView(SystemUIDialog dialog) {
        return dialog.requireViewById(R.id.related_tools_container);
    }

    @After
    public void reset() {
        if (mDialogDelegate != null) {
            mDialogDelegate = null;
        }
        if (mDialog != null) {
            mDialog.dismiss();
        }
    }
}
