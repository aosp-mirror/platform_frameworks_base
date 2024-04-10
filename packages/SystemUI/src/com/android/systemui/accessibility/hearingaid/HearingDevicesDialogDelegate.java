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

import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothHapPresetInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.Visibility;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.bluetooth.BluetoothCallback;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.HapClientProfile;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.systemui.accessibility.hearingaid.HearingDevicesListAdapter.HearingDeviceItemCallback;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.bluetooth.qsdialog.ActiveHearingDeviceItemFactory;
import com.android.systemui.bluetooth.qsdialog.AvailableHearingDeviceItemFactory;
import com.android.systemui.bluetooth.qsdialog.ConnectedDeviceItemFactory;
import com.android.systemui.bluetooth.qsdialog.DeviceItem;
import com.android.systemui.bluetooth.qsdialog.DeviceItemFactory;
import com.android.systemui.bluetooth.qsdialog.DeviceItemType;
import com.android.systemui.bluetooth.qsdialog.SavedHearingDeviceItemFactory;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Dialog for showing hearing devices controls.
 */
public class HearingDevicesDialogDelegate implements SystemUIDialog.Delegate,
        HearingDeviceItemCallback, BluetoothCallback {
    private static final String TAG = "HearingDevicesDialogDelegate";
    @VisibleForTesting
    static final String ACTION_BLUETOOTH_DEVICE_DETAILS =
            "com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS";
    private static final String EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args";
    private static final String KEY_BLUETOOTH_ADDRESS = "device_address";
    @VisibleForTesting
    static final Intent LIVE_CAPTION_INTENT = new Intent(
            "com.android.settings.action.live_caption");
    private final SystemUIDialog.Factory mSystemUIDialogFactory;
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final ActivityStarter mActivityStarter;
    private final boolean mShowPairNewDevice;
    private final LocalBluetoothManager mLocalBluetoothManager;
    private final Handler mMainHandler;
    private final AudioManager mAudioManager;
    private final LocalBluetoothProfileManager mProfileManager;
    private final HapClientProfile mHapClientProfile;
    private HearingDevicesListAdapter mDeviceListAdapter;
    private HearingDevicesPresetsController mPresetsController;
    private Context mApplicationContext;
    private SystemUIDialog mDialog;
    private RecyclerView mDeviceList;
    private List<DeviceItem> mHearingDeviceItemList;
    private Spinner mPresetSpinner;
    private ArrayAdapter<String> mPresetInfoAdapter;
    private Button mPairButton;
    private LinearLayout mRelatedToolsContainer;
    private final HearingDevicesPresetsController.PresetCallback mPresetCallback =
            new HearingDevicesPresetsController.PresetCallback() {
                @Override
                public void onPresetInfoUpdated(List<BluetoothHapPresetInfo> presetInfos,
                        int activePresetIndex) {
                    mMainHandler.post(
                            () -> refreshPresetInfoAdapter(presetInfos, activePresetIndex));
                }

                @Override
                public void onPresetCommandFailed(int reason) {
                    final List<BluetoothHapPresetInfo> presetInfos =
                            mPresetsController.getAllPresetInfo();
                    final int activePresetIndex = mPresetsController.getActivePresetIndex();
                    mMainHandler.post(() -> {
                        refreshPresetInfoAdapter(presetInfos, activePresetIndex);
                        showPresetErrorToast(mApplicationContext);
                    });
                }
            };
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
            @Application Context applicationContext,
            @Assisted boolean showPairNewDevice,
            SystemUIDialog.Factory systemUIDialogFactory,
            ActivityStarter activityStarter,
            DialogTransitionAnimator dialogTransitionAnimator,
            @Nullable LocalBluetoothManager localBluetoothManager,
            @Main Handler handler,
            AudioManager audioManager) {
        mApplicationContext = applicationContext;
        mShowPairNewDevice = showPairNewDevice;
        mSystemUIDialogFactory = systemUIDialogFactory;
        mActivityStarter = activityStarter;
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mLocalBluetoothManager = localBluetoothManager;
        mMainHandler = handler;
        mAudioManager = audioManager;
        mProfileManager = localBluetoothManager.getProfileManager();
        mHapClientProfile = mProfileManager.getHapClientProfile();
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
        CachedBluetoothDevice activeHearingDevice;
        mHearingDeviceItemList = getHearingDevicesList();
        if (mPresetsController != null) {
            activeHearingDevice = getActiveHearingDevice(mHearingDeviceItemList);
            mPresetsController.setActiveHearingDevice(activeHearingDevice);
        } else {
            activeHearingDevice = null;
        }
        mMainHandler.post(() -> {
            mDeviceListAdapter.refreshDeviceItemList(mHearingDeviceItemList);
            final List<BluetoothHapPresetInfo> presetInfos =
                    mPresetsController.getAllPresetInfo();
            final int activePresetIndex = mPresetsController.getActivePresetIndex();
            refreshPresetInfoAdapter(presetInfos, activePresetIndex);
            mPresetSpinner.setVisibility(
                    (activeHearingDevice != null && !mPresetInfoAdapter.isEmpty()) ? VISIBLE
                            : GONE);
        });
    }

    @Override
    public void onProfileConnectionStateChanged(@NonNull CachedBluetoothDevice cachedDevice,
            int state, int bluetoothProfile) {
        mHearingDeviceItemList = getHearingDevicesList();
        mMainHandler.post(() -> mDeviceListAdapter.refreshDeviceItemList(mHearingDeviceItemList));
    }

    @Override
    public void onAclConnectionStateChanged(@NonNull CachedBluetoothDevice cachedDevice,
            int state) {
        mHearingDeviceItemList = getHearingDevicesList();
        mMainHandler.post(() -> mDeviceListAdapter.refreshDeviceItemList(mHearingDeviceItemList));
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
        if (mLocalBluetoothManager == null) {
            return;
        }
        mPairButton = dialog.requireViewById(R.id.pair_new_device_button);
        mDeviceList = dialog.requireViewById(R.id.device_list);
        mPresetSpinner = dialog.requireViewById(R.id.preset_spinner);
        mRelatedToolsContainer = dialog.requireViewById(R.id.related_tools_container);

        setupDeviceListView(dialog);
        setupPresetSpinner(dialog);
        setupPairNewDeviceButton(dialog, mShowPairNewDevice ? VISIBLE : GONE);
        setupRelatedToolsView(dialog);
    }

    @Override
    public void onStart(@NonNull SystemUIDialog dialog) {
        if (mLocalBluetoothManager == null) {
            return;
        }

        mLocalBluetoothManager.getEventManager().registerCallback(this);
        if (mPresetsController != null) {
            mPresetsController.registerHapCallback();
            if (mHapClientProfile != null && !mHapClientProfile.isProfileReady()) {
                mProfileManager.addServiceListener(mPresetsController);
            }
        }
    }

    @Override
    public void onStop(@NonNull SystemUIDialog dialog) {
        if (mLocalBluetoothManager == null) {
            return;
        }

        if (mPresetsController != null) {
            mPresetsController.unregisterHapCallback();
            mProfileManager.removeServiceListener(mPresetsController);
        }
        mLocalBluetoothManager.getEventManager().unregisterCallback(this);
    }

    private void setupDeviceListView(SystemUIDialog dialog) {
        mDeviceList.setLayoutManager(new LinearLayoutManager(dialog.getContext()));
        mHearingDeviceItemList = getHearingDevicesList();
        mDeviceListAdapter = new HearingDevicesListAdapter(mHearingDeviceItemList, this);
        mDeviceList.setAdapter(mDeviceListAdapter);
    }

    private void setupPresetSpinner(SystemUIDialog dialog) {
        mPresetsController = new HearingDevicesPresetsController(mProfileManager, mPresetCallback);
        final CachedBluetoothDevice activeHearingDevice = getActiveHearingDevice(
                mHearingDeviceItemList);
        mPresetsController.setActiveHearingDevice(activeHearingDevice);

        mPresetInfoAdapter = new ArrayAdapter<String>(dialog.getContext(),
                R.layout.hearing_devices_preset_spinner_selected,
                R.id.hearing_devices_preset_option_text);
        mPresetInfoAdapter.setDropDownViewResource(
                R.layout.hearing_devices_preset_dropdown_item);
        mPresetSpinner.setAdapter(mPresetInfoAdapter);

        // disable redundant Touch & Hold accessibility action for Switch Access
        mPresetSpinner.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                    @NonNull AccessibilityNodeInfo info) {
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
                super.onInitializeAccessibilityNodeInfo(host, info);
            }
        });

        mPresetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mPresetsController.selectPreset(
                        mPresetsController.getAllPresetInfo().get(position).getIndex());
                mPresetSpinner.setSelection(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Do nothing
            }
        });
        final List<BluetoothHapPresetInfo> presetInfos = mPresetsController.getAllPresetInfo();
        final int activePresetIndex = mPresetsController.getActivePresetIndex();
        refreshPresetInfoAdapter(presetInfos, activePresetIndex);
        mPresetSpinner.setVisibility(
                (activeHearingDevice != null && !mPresetInfoAdapter.isEmpty()) ? VISIBLE : GONE);
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

    private void setupRelatedToolsView(SystemUIDialog dialog) {
        final Context context = dialog.getContext();
        final List<ToolItem> toolItemList = new ArrayList<>();
        final String[] toolNameArray;
        final String[] toolIconArray;

        ToolItem preInstalledItem = getLiveCaption(context);
        if (preInstalledItem != null) {
            toolItemList.add(preInstalledItem);
        }
        try {
            toolNameArray = context.getResources().getStringArray(
                    R.array.config_quickSettingsHearingDevicesRelatedToolName);
            toolIconArray = context.getResources().getStringArray(
                    R.array.config_quickSettingsHearingDevicesRelatedToolIcon);
            toolItemList.addAll(
                    HearingDevicesToolItemParser.parseStringArray(context, toolNameArray,
                    toolIconArray));
        } catch (Resources.NotFoundException e) {
            Log.i(TAG, "No hearing devices related tool config resource");
        }
        final int listSize = toolItemList.size();
        for (int i = 0; i < listSize; i++) {
            View view = createHearingToolView(context, toolItemList.get(i));
            mRelatedToolsContainer.addView(view);
        }
    }

    private void refreshPresetInfoAdapter(List<BluetoothHapPresetInfo> presetInfos,
            int activePresetIndex) {
        mPresetInfoAdapter.clear();
        mPresetInfoAdapter.addAll(
                presetInfos.stream().map(BluetoothHapPresetInfo::getName).toList());
        if (activePresetIndex != BluetoothHapClient.PRESET_INDEX_UNAVAILABLE) {
            final int size = mPresetInfoAdapter.getCount();
            for (int position = 0; position < size; position++) {
                if (presetInfos.get(position).getIndex() == activePresetIndex) {
                    mPresetSpinner.setSelection(position);
                }
            }
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

    @Nullable
    private CachedBluetoothDevice getActiveHearingDevice(List<DeviceItem> hearingDeviceItemList) {
        return hearingDeviceItemList.stream()
                .filter(item -> item.getType() == DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE)
                .map(DeviceItem::getCachedBluetoothDevice)
                .findFirst()
                .orElse(null);
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

    @NonNull
    private View createHearingToolView(Context context, ToolItem item) {
        View view = LayoutInflater.from(context).inflate(R.layout.hearing_tool_item,
                mRelatedToolsContainer, false);
        ImageView icon = view.requireViewById(R.id.tool_icon);
        TextView text = view.requireViewById(R.id.tool_name);
        view.setContentDescription(item.getToolName());
        icon.setImageDrawable(item.getToolIcon());
        text.setText(item.getToolName());
        Intent intent = item.getToolIntent();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        view.setOnClickListener(
                v -> {
                    dismissDialogIfExists();
                    mActivityStarter.postStartActivityDismissingKeyguard(intent, /* delay= */ 0,
                            mDialogTransitionAnimator.createActivityTransitionController(view));
                });
        return view;
    }

    private ToolItem getLiveCaption(Context context) {
        final PackageManager packageManager = context.getPackageManager();
        LIVE_CAPTION_INTENT.setPackage(packageManager.getSystemCaptionsServicePackageName());
        final List<ResolveInfo> resolved = packageManager.queryIntentActivities(LIVE_CAPTION_INTENT,
                /* flags= */ 0);
        if (!resolved.isEmpty()) {
            return new ToolItem(context.getString(R.string.live_caption_title),
                    context.getDrawable(R.drawable.ic_volume_odi_captions),
                    LIVE_CAPTION_INTENT);
        }

        return null;
    }

    private void dismissDialogIfExists() {
        if (mDialog != null) {
            mDialog.dismiss();
        }
    }

    private void showPresetErrorToast(Context context) {
        Toast.makeText(context, R.string.hearing_devices_presets_error, Toast.LENGTH_SHORT).show();
    }
}
