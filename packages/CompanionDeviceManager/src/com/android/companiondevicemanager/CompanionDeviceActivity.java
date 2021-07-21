/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.companiondevicemanager;

import static android.companion.BluetoothDeviceFilterUtils.getDeviceMacAddress;
import static android.text.TextUtils.emptyIfNull;
import static android.text.TextUtils.isEmpty;
import static android.text.TextUtils.withoutPrefix;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Html;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.companiondevicemanager.CompanionDeviceDiscoveryService.DeviceFilterPair;
import com.android.internal.util.Preconditions;

public class CompanionDeviceActivity extends Activity {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = CompanionDeviceActivity.class.getSimpleName();

    static CompanionDeviceActivity sInstance;

    View mLoadingIndicator = null;
    ListView mDeviceListView;
    private View mPairButton;
    private View mCancelButton;

    DevicesAdapter mDevicesAdapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(LOG_TAG, "Starting UI for " + getService().mRequest);

        if (getService().mDevicesFound.isEmpty()) {
            Log.e(LOG_TAG, "About to show UI, but no devices to show");
        }

        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        sInstance = this;

        String deviceProfile = getRequest().getDeviceProfile();
        String profilePrivacyDisclaimer = emptyIfNull(getRequest()
                .getDeviceProfilePrivilegesDescription())
                .replace("APP_NAME", getCallingAppName());
        boolean useDeviceProfile = deviceProfile != null && !isEmpty(profilePrivacyDisclaimer);
        String profileName = useDeviceProfile
                ? getDeviceProfileName(deviceProfile)
                : getString(R.string.profile_name_generic);

        if (getRequest().isSingleDevice()) {
            setContentView(R.layout.device_confirmation);
            final DeviceFilterPair selectedDevice = getService().mDevicesFound.get(0);
            setTitle(Html.fromHtml(getString(
                    R.string.confirmation_title,
                    getCallingAppName(),
                    profileName,
                    selectedDevice.getDisplayName()), 0));
            mPairButton = findViewById(R.id.button_pair);
            mPairButton.setOnClickListener(v -> onDeviceConfirmed(getService().mSelectedDevice));
            getService().mSelectedDevice = selectedDevice;
            onSelectionUpdate();
            if (getRequest().isSkipPrompt()) {
                onDeviceConfirmed(selectedDevice);
            }
        } else {
            setContentView(R.layout.device_chooser);
            mPairButton = findViewById(R.id.button_pair);
            mPairButton.setVisibility(View.GONE);
            setTitle(Html.fromHtml(getString(R.string.chooser_title,
                    profileName,
                    getCallingAppName()), 0));
            mDeviceListView = findViewById(R.id.device_list);
            mDevicesAdapter = new DevicesAdapter();
            mDeviceListView.setAdapter(mDevicesAdapter);
            mDeviceListView.setOnItemClickListener((adapterView, view, pos, l) -> {
                getService().mSelectedDevice =
                        (DeviceFilterPair) adapterView.getItemAtPosition(pos);
                mDevicesAdapter.notifyDataSetChanged();
            });
            mDevicesAdapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    onSelectionUpdate();
                }
            });
            mDeviceListView.addFooterView(mLoadingIndicator = getProgressBar(), null, false);
        }

        TextView profileSummary = findViewById(R.id.profile_summary);

        if (useDeviceProfile) {
            profileSummary.setVisibility(View.VISIBLE);
            String deviceRef = getRequest().isSingleDevice()
                    ? getService().mDevicesFound.get(0).getDisplayName()
                    : profileName;
            profileSummary.setText(getString(R.string.profile_summary,
                    deviceRef,
                    profilePrivacyDisclaimer));
        } else {
            profileSummary.setVisibility(View.GONE);
        }

        getService().mActivity = this;

        mCancelButton = findViewById(R.id.button_cancel);
        mCancelButton.setOnClickListener(v -> cancel());
    }

    static void notifyDevicesChanged() {
        if (sInstance != null && sInstance.mDevicesAdapter != null && !sInstance.isFinishing()) {
            sInstance.mDevicesAdapter.notifyDataSetChanged();
        }
    }

    private AssociationRequest getRequest() {
        return getService().mRequest;
    }

    private String getDeviceProfileName(@Nullable String deviceProfile) {
        if (deviceProfile == null) {
            return getString(R.string.profile_name_generic);
        }
        switch (deviceProfile) {
            case AssociationRequest.DEVICE_PROFILE_WATCH: {
                return getString(R.string.profile_name_watch);
            }
            default: {
                Log.w(LOG_TAG,
                        "No localized profile name found for device profile: " + deviceProfile);
                return withoutPrefix("android.app.role.COMPANION_DEVICE_", deviceProfile)
                        .toLowerCase()
                        .replace('_', ' ');
            }
        }
    }

    private void cancel() {
        Log.i(LOG_TAG, "cancel()");
        getService().onCancel();
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing() && !isChangingConfigurations()) {
            Log.i(LOG_TAG, "onStop() - cancelling");
            cancel();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sInstance == this) {
            sInstance = null;
        }
    }

    private CharSequence getCallingAppName() {
        try {
            final PackageManager packageManager = getPackageManager();
            String callingPackage = Preconditions.checkStringNotEmpty(
                    getCallingPackage(),
                    "This activity must be called for result");
            return packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(callingPackage, 0));
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getCallingPackage() {
        return requireNonNull(getRequest().getCallingPackage());
    }

    @Override
    public void setTitle(CharSequence title) {
        final TextView titleView = findViewById(R.id.title);
        final int padding = getPadding(getResources());
        titleView.setPadding(padding, padding, padding, padding);
        titleView.setText(title);
    }

    private ProgressBar getProgressBar() {
        final ProgressBar progressBar = new ProgressBar(this);
        progressBar.setForegroundGravity(Gravity.CENTER_HORIZONTAL);
        final int padding = getPadding(getResources());
        progressBar.setPadding(padding, padding, padding, padding);
        return progressBar;
    }

    static int getPadding(Resources r) {
        return r.getDimensionPixelSize(R.dimen.padding);
    }

    private void onSelectionUpdate() {
        DeviceFilterPair selectedDevice = getService().mSelectedDevice;
        if (mPairButton.getVisibility() != View.VISIBLE && selectedDevice != null) {
            onDeviceConfirmed(selectedDevice);
        } else {
            mPairButton.setEnabled(selectedDevice != null);
        }
    }

    private CompanionDeviceDiscoveryService getService() {
        return CompanionDeviceDiscoveryService.sInstance;
    }

    protected void onDeviceConfirmed(DeviceFilterPair selectedDevice) {
        Log.i(LOG_TAG, "onDeviceConfirmed(selectedDevice = " + selectedDevice + ")");
        getService().onDeviceSelected(
                getCallingPackage(), getDeviceMacAddress(selectedDevice.device));
        setResult(RESULT_OK,
                new Intent().putExtra(CompanionDeviceManager.EXTRA_DEVICE, selectedDevice.device));
        finish();
    }

    class DevicesAdapter extends BaseAdapter {
        private final Drawable mBluetoothIcon = icon(android.R.drawable.stat_sys_data_bluetooth);
        private final Drawable mWifiIcon = icon(com.android.internal.R.drawable.ic_wifi_signal_3);

        private SparseArray<Integer> mColors = new SparseArray();

        private Drawable icon(int drawableRes) {
            Drawable icon = getResources().getDrawable(drawableRes, null);
            icon.setTint(Color.DKGRAY);
            return icon;
        }

        @Override
        public View getView(
                int position,
                @Nullable View convertView,
                @NonNull ViewGroup parent) {
            TextView view = convertView instanceof TextView
                    ? (TextView) convertView
                    : newView();
            bind(view, getItem(position));
            return view;
        }

        private void bind(TextView textView, DeviceFilterPair device) {
            textView.setText(device.getDisplayName());
            textView.setBackgroundColor(
                    device.equals(getService().mSelectedDevice)
                            ? getColor(android.R.attr.colorControlHighlight)
                            : Color.TRANSPARENT);
            textView.setCompoundDrawablesWithIntrinsicBounds(
                    device.device instanceof android.net.wifi.ScanResult
                            ? mWifiIcon
                            : mBluetoothIcon,
                    null, null, null);
            textView.getCompoundDrawables()[0].setTint(getColor(android.R.attr.colorForeground));
        }

        private TextView newView() {
            final TextView textView = new TextView(CompanionDeviceActivity.this);
            textView.setTextColor(getColor(android.R.attr.colorForeground));
            final int padding = CompanionDeviceActivity.getPadding(getResources());
            textView.setPadding(padding, padding, padding, padding);
            textView.setCompoundDrawablePadding(padding);
            return textView;
        }

        private int getColor(int colorAttr) {
            if (mColors.contains(colorAttr)) {
                return mColors.get(colorAttr);
            }
            TypedValue typedValue = new TypedValue();
            TypedArray a = obtainStyledAttributes(typedValue.data, new int[] { colorAttr });
            int result = a.getColor(0, 0);
            a.recycle();
            mColors.put(colorAttr, result);
            return result;
        }

        @Override
        public int getCount() {
            return getService().mDevicesFound.size();
        }

        @Override
        public DeviceFilterPair getItem(int position) {
            return getService().mDevicesFound.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }
    }
}
