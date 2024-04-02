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

import static java.util.Collections.emptyList;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.Visibility;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.bluetooth.BluetoothCallback;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.accessibility.hearingaid.HearingDevicesListAdapter.HearingDeviceItemCallback;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.tiles.dialog.bluetooth.ActiveHearingDeviceItemFactory;
import com.android.systemui.qs.tiles.dialog.bluetooth.AvailableHearingDeviceItemFactory;
import com.android.systemui.qs.tiles.dialog.bluetooth.ConnectedDeviceItemFactory;
import com.android.systemui.qs.tiles.dialog.bluetooth.DeviceItem;
import com.android.systemui.qs.tiles.dialog.bluetooth.DeviceItemFactory;
import com.android.systemui.qs.tiles.dialog.bluetooth.SavedHearingDeviceItemFactory;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Dialog for showing hearing devices controls.
 */
public class HearingDevicesDialogDelegate implements SystemUIDialog.Delegate,
        HearingDeviceItemCallback, BluetoothCallback {

    @VisibleForTesting
    static final String ACTION_BLUETOOTH_DEVICE_DETAILS =
            "com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS";
    private static final String EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args";
    private static final String KEY_BLUETOOTH_ADDRESS = "device_address";
    private final SystemUIDialog.Factory mSystemUIDialogFactory;
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final ActivityStarter mActivityStarter;
    private final boolean mShowPairNewDevice;
    private final LocalBluetoothManager mLocalBluetoothManager;
    private final Handler mMainHandler;
    private final AudioManager mAudioManager;

    private HearingDevicesListAdapter mDeviceListAdapter;
    private SystemUIDialog mDialog;
    private RecyclerView mDeviceList;
    private Button mPairButton;
    private final List<DeviceItemFactory> mHearingDeviceItemFactoryList = List.of(
            new ActiveHearingDeviceItemFactory(),
            new AvailableHearingDeviceItemFactory(),
            // TODO(b/331305850): setHearingAidInfo() for connected but not connect to profile
            // hearing device only called from
            // settings/bluetooth/DeviceListPreferenceFragment#handleLeScanResult, so we don't know
            // it is connected but not yet connect to profile hearing device in systemui.
            // Show all connected but not connect to profile bluetooth device for now.
            new ConnectedDeviceItemFactory(),
            new SavedHearingDeviceItemFactory()
    );

    /** Factory to create a {@link HearingDevicesDialogDelegate} dialog instance. */
    @AssistedFactory
    public interface Factory {
        /** Create a {@link HearingDevicesDialogDelegate} instance */
        HearingDevicesDialogDelegate create(
                boolean showPairNewDevice);
    }

    @AssistedInject
    public HearingDevicesDialogDelegate(
            @Assisted boolean showPairNewDevice,
            SystemUIDialog.Factory systemUIDialogFactory,
            ActivityStarter activityStarter,
            DialogTransitionAnimator dialogTransitionAnimator,
            @Nullable LocalBluetoothManager localBluetoothManager,
            @Main Handler handler,
            AudioManager audioManager) {
        mShowPairNewDevice = showPairNewDevice;
        mSystemUIDialogFactory = systemUIDialogFactory;
        mActivityStarter = activityStarter;
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mLocalBluetoothManager = localBluetoothManager;
        mMainHandler = handler;
        mAudioManager = audioManager;
    }

    @Override
    public SystemUIDialog createDialog() {
        SystemUIDialog dialog = mSystemUIDialogFactory.create(this);
        dismissDialogIfExists();
        mDialog = dialog;

        return dialog;
    }

    @Override
    public void onDeviceItemGearClicked(@NonNull  DeviceItem deviceItem, @NonNull View view) {
        dismissDialogIfExists();
        Intent intent = new Intent(ACTION_BLUETOOTH_DEVICE_DETAILS);
        Bundle bundle = new Bundle();
        bundle.putString(KEY_BLUETOOTH_ADDRESS, deviceItem.getCachedBluetoothDevice().getAddress());
        intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, bundle);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mActivityStarter.postStartActivityDismissingKeyguard(intent, /* delay= */ 0,
                mDialogTransitionAnimator.createActivityTransitionController(view));
    }

    @Override
    public void onDeviceItemOnClicked(@NonNull  DeviceItem deviceItem, @NonNull View view) {
        CachedBluetoothDevice cachedBluetoothDevice = deviceItem.getCachedBluetoothDevice();
        switch (deviceItem.getType()) {
            case ACTIVE_MEDIA_BLUETOOTH_DEVICE, CONNECTED_BLUETOOTH_DEVICE ->
                    cachedBluetoothDevice.disconnect();
            case AVAILABLE_MEDIA_BLUETOOTH_DEVICE -> cachedBluetoothDevice.setActive();
            case SAVED_BLUETOOTH_DEVICE -> cachedBluetoothDevice.connect();
        }
    }

    @Override
    public void onActiveDeviceChanged(@Nullable CachedBluetoothDevice activeDevice,
            int bluetoothProfile) {
        mMainHandler.post(() -> mDeviceListAdapter.refreshDeviceItemList(getHearingDevicesList()));
    }

    @Override
    public void onProfileConnectionStateChanged(@NonNull CachedBluetoothDevice cachedDevice,
            int state, int bluetoothProfile) {
        mMainHandler.post(() -> mDeviceListAdapter.refreshDeviceItemList(getHearingDevicesList()));
    }

    @Override
    public void onAclConnectionStateChanged(@NonNull CachedBluetoothDevice cachedDevice,
            int state) {
        mMainHandler.post(() -> mDeviceListAdapter.refreshDeviceItemList(getHearingDevicesList()));
    }

    @Override
    public void beforeCreate(@NonNull SystemUIDialog dialog, @Nullable Bundle savedInstanceState) {
        dialog.setTitle(R.string.quick_settings_hearing_devices_dialog_title);
        dialog.setView(LayoutInflater.from(dialog.getContext()).inflate(
                R.layout.hearing_devices_tile_dialog, null));
        dialog.setPositiveButton(
                R.string.quick_settings_done,
                /* onClick = */ null,
                /* dismissOnClick = */ true
        );
    }

    @Override
    public void onCreate(@NonNull SystemUIDialog dialog, @Nullable Bundle savedInstanceState) {
        mPairButton = dialog.requireViewById(R.id.pair_new_device_button);
        mDeviceList = dialog.requireViewById(R.id.device_list);

        setupDeviceListView(dialog);
        setupPairNewDeviceButton(dialog, mShowPairNewDevice ? VISIBLE : GONE);
    }

    @Override
    public void onStart(@NonNull SystemUIDialog dialog) {
        if (mLocalBluetoothManager == null) {
            return;
        }
        mLocalBluetoothManager.getEventManager().registerCallback(this);
    }

    @Override
    public void onStop(@NonNull SystemUIDialog dialog) {
        if (mLocalBluetoothManager == null) {
            return;
        }
        mLocalBluetoothManager.getEventManager().unregisterCallback(this);
    }

    private void setupDeviceListView(SystemUIDialog dialog) {
        mDeviceList.setLayoutManager(new LinearLayoutManager(dialog.getContext()));
        mDeviceListAdapter = new HearingDevicesListAdapter(getHearingDevicesList(), this);
        mDeviceList.setAdapter(mDeviceListAdapter);
    }

    private void setupPairNewDeviceButton(SystemUIDialog dialog, @Visibility int visibility) {
        if (visibility == VISIBLE) {
            mPairButton.setOnClickListener(v -> {
                dismissDialogIfExists();
                final Intent intent = new Intent(Settings.ACTION_HEARING_DEVICE_PAIRING_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                mActivityStarter.postStartActivityDismissingKeyguard(intent, /* delay= */ 0,
                        mDialogTransitionAnimator.createActivityTransitionController(dialog));
            });
        } else {
            mPairButton.setVisibility(GONE);
        }
    }

    private List<DeviceItem> getHearingDevicesList() {
        if (mLocalBluetoothManager == null
                || !mLocalBluetoothManager.getBluetoothAdapter().isEnabled()) {
            return emptyList();
        }

        return mLocalBluetoothManager.getCachedDeviceManager().getCachedDevicesCopy().stream()
                .map(this::createHearingDeviceItem)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private DeviceItem createHearingDeviceItem(CachedBluetoothDevice cachedDevice) {
        final Context context = mDialog.getContext();
        if (cachedDevice == null) {
            return null;
        }
        for (DeviceItemFactory itemFactory : mHearingDeviceItemFactoryList) {
            if (itemFactory.isFilterMatched(context, cachedDevice, mAudioManager)) {
                return itemFactory.create(context, cachedDevice);
            }
        }
        return null;
    }

    private void dismissDialogIfExists() {
        if (mDialog != null) {
            mDialog.dismiss();
        }
    }
}
