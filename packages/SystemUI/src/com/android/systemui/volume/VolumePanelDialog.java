/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.volume;

import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.SettingsSlicesContract;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.slice.Slice;
import androidx.slice.SliceMetadata;
import androidx.slice.widget.EventInfo;
import androidx.slice.widget.SliceLiveData;

import com.android.settingslib.bluetooth.A2dpProfile;
import com.android.settingslib.bluetooth.BluetoothUtils;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.settingslib.media.MediaOutputConstants;
import com.android.systemui.res.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Visual presentation of the volume panel dialog.
 */
public class VolumePanelDialog extends SystemUIDialog implements LifecycleOwner {
    private static final String TAG = "VolumePanelDialog";

    private static final int DURATION_SLICE_BINDING_TIMEOUT_MS = 200;
    private static final int DEFAULT_SLICE_SIZE = 4;

    private final ActivityStarter mActivityStarter;
    private RecyclerView mVolumePanelSlices;
    private VolumePanelSlicesAdapter mVolumePanelSlicesAdapter;
    private final LifecycleRegistry mLifecycleRegistry;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<Uri, LiveData<Slice>> mSliceLiveData = new LinkedHashMap<>();
    private final HashSet<Uri> mLoadedSlices = new HashSet<>();
    private boolean mSlicesReadyToLoad;
    private LocalBluetoothProfileManager mProfileManager;

    public VolumePanelDialog(Context context,
            ActivityStarter activityStarter, boolean aboveStatusBar) {
        super(context);
        mActivityStarter = activityStarter;
        mLifecycleRegistry = new LifecycleRegistry(this);
        if (!aboveStatusBar) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");

        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.volume_panel_dialog,
                null);
        final Window window = getWindow();
        window.setContentView(dialogView);

        Button doneButton = dialogView.findViewById(R.id.done_button);
        doneButton.setOnClickListener(v -> dismiss());
        Button settingsButton = dialogView.findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            dismiss();

            Intent intent = new Intent(Settings.ACTION_SOUND_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mActivityStarter.startActivity(intent, /* dismissShade= */ true);
        });

        LocalBluetoothManager localBluetoothManager = LocalBluetoothManager.getInstance(
                getContext(), null);
        if (localBluetoothManager != null) {
            mProfileManager = localBluetoothManager.getProfileManager();
        }

        mVolumePanelSlices = dialogView.findViewById(R.id.volume_panel_parent_layout);
        mVolumePanelSlices.setLayoutManager(new LinearLayoutManager(getContext()));

        loadAllSlices();

        mLifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
    }

    private void loadAllSlices() {
        mSliceLiveData.clear();
        mLoadedSlices.clear();
        final List<Uri> sliceUris = getSlices();

        for (Uri uri : sliceUris) {
            final LiveData<Slice> sliceLiveData = SliceLiveData.fromUri(getContext(), uri,
                    (int type, Throwable source) -> {
                        if (!removeSliceLiveData(uri)) {
                            mLoadedSlices.add(uri);
                        }
                    });

            // Add slice first to make it in order.  Will remove it later if there's an error.
            mSliceLiveData.put(uri, sliceLiveData);

            sliceLiveData.observe(this, slice -> {
                if (mLoadedSlices.contains(uri)) {
                    return;
                }
                Log.d(TAG, "received slice: " + (slice == null ? null : slice.getUri()));
                final SliceMetadata metadata = SliceMetadata.from(getContext(), slice);
                if (slice == null || metadata.isErrorSlice()) {
                    if (!removeSliceLiveData(uri)) {
                        mLoadedSlices.add(uri);
                    }
                } else if (metadata.getLoadingState() == SliceMetadata.LOADED_ALL) {
                    mLoadedSlices.add(uri);
                } else {
                    mHandler.postDelayed(() -> {
                        mLoadedSlices.add(uri);
                        setupAdapterWhenReady();
                    }, DURATION_SLICE_BINDING_TIMEOUT_MS);
                }

                setupAdapterWhenReady();
            });
        }
    }

    private void setupAdapterWhenReady() {
        if (mLoadedSlices.size() == mSliceLiveData.size() && !mSlicesReadyToLoad) {
            mSlicesReadyToLoad = true;
            mVolumePanelSlicesAdapter = new VolumePanelSlicesAdapter(this, mSliceLiveData);
            mVolumePanelSlicesAdapter.setOnSliceActionListener((eventInfo, sliceItem) -> {
                if (eventInfo.actionType == EventInfo.ACTION_TYPE_SLIDER) {
                    return;
                }
                this.dismiss();
            });
            if (mSliceLiveData.size() < DEFAULT_SLICE_SIZE) {
                mVolumePanelSlices.setMinimumHeight(0);
            }
            mVolumePanelSlices.setAdapter(mVolumePanelSlicesAdapter);
        }
    }

    private boolean removeSliceLiveData(Uri uri) {
        boolean removed = false;
        // Keeps observe media output slice
        if (!uri.equals(MEDIA_OUTPUT_INDICATOR_SLICE_URI)) {
            Log.d(TAG, "remove uri: " + uri);
            removed = mSliceLiveData.remove(uri) != null;
            if (mVolumePanelSlicesAdapter != null) {
                mVolumePanelSlicesAdapter.updateDataSet(new ArrayList<>(mSliceLiveData.values()));
            }
        }
        return removed;
    }

    @Override
    protected void start() {
        Log.d(TAG, "onStart");
        mLifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        mLifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
    }

    @Override
    protected void stop() {
        Log.d(TAG, "onStop");
        mLifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
    }

    private List<Uri> getSlices() {
        final List<Uri> uris = new ArrayList<>();
        uris.add(REMOTE_MEDIA_SLICE_URI);
        uris.add(VOLUME_MEDIA_URI);
        Uri controlUri = getExtraControlUri();
        if (controlUri != null) {
            Log.d(TAG, "add extra control slice");
            uris.add(controlUri);
        }
        uris.add(MEDIA_OUTPUT_INDICATOR_SLICE_URI);
        uris.add(VOLUME_CALL_URI);
        uris.add(VOLUME_RINGER_URI);
        uris.add(VOLUME_ALARM_URI);
        return uris;
    }

    private static final String SETTINGS_SLICE_AUTHORITY = "com.android.settings.slices";
    private static final Uri REMOTE_MEDIA_SLICE_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(SETTINGS_SLICE_AUTHORITY)
            .appendPath(SettingsSlicesContract.PATH_SETTING_ACTION)
            .appendPath(MediaOutputConstants.KEY_REMOTE_MEDIA)
            .build();
    private static final Uri VOLUME_MEDIA_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(SETTINGS_SLICE_AUTHORITY)
            .appendPath(SettingsSlicesContract.PATH_SETTING_ACTION)
            .appendPath("media_volume")
            .build();
    private static final Uri MEDIA_OUTPUT_INDICATOR_SLICE_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(SETTINGS_SLICE_AUTHORITY)
            .appendPath(SettingsSlicesContract.PATH_SETTING_INTENT)
            .appendPath("media_output_indicator")
            .build();
    private static final Uri VOLUME_CALL_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(SETTINGS_SLICE_AUTHORITY)
            .appendPath(SettingsSlicesContract.PATH_SETTING_ACTION)
            .appendPath("call_volume")
            .build();
    private static final Uri VOLUME_RINGER_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(SETTINGS_SLICE_AUTHORITY)
            .appendPath(SettingsSlicesContract.PATH_SETTING_ACTION)
            .appendPath("ring_volume")
            .build();
    private static final Uri VOLUME_ALARM_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(SETTINGS_SLICE_AUTHORITY)
            .appendPath(SettingsSlicesContract.PATH_SETTING_ACTION)
            .appendPath("alarm_volume")
            .build();

    private Uri getExtraControlUri() {
        Uri controlUri = null;
        final BluetoothDevice bluetoothDevice = findActiveDevice();
        if (bluetoothDevice != null) {
            // The control slice width = dialog width - horizontal padding of two sides
            final int dialogWidth =
                    getWindow().getWindowManager().getCurrentWindowMetrics().getBounds().width();
            final int controlSliceWidth = dialogWidth
                    - getContext().getResources().getDimensionPixelSize(
                    R.dimen.volume_panel_slice_horizontal_padding) * 2;
            final String uri = BluetoothUtils.getControlUriMetaData(bluetoothDevice);
            if (!TextUtils.isEmpty(uri)) {
                try {
                    controlUri = Uri.parse(uri + controlSliceWidth);
                } catch (NullPointerException exception) {
                    Log.d(TAG, "unable to parse extra control uri");
                    controlUri = null;
                }
            }
        }
        return controlUri;
    }

    private BluetoothDevice findActiveDevice() {
        if (mProfileManager != null) {
            final A2dpProfile a2dpProfile = mProfileManager.getA2dpProfile();
            if (a2dpProfile != null) {
                return a2dpProfile.getActiveDevice();
            }
        }
        return null;
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycleRegistry;
    }
}
