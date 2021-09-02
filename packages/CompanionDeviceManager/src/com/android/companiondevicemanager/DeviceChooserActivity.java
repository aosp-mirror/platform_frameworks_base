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
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static java.util.Objects.requireNonNull;

import android.app.Activity;
import android.companion.CompanionDeviceManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.text.Html;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.companiondevicemanager.DeviceDiscoveryService.DeviceFilterPair;
import com.android.internal.util.Preconditions;

public class DeviceChooserActivity extends Activity {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "DeviceChooserActivity";

    View mLoadingIndicator = null;
    ListView mDeviceListView;
    private View mPairButton;
    private View mCancelButton;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (DEBUG) Log.i(LOG_TAG, "Started with intent " + getIntent());

        if (getService().mDevicesFound.isEmpty()) {
            Log.e(LOG_TAG, "About to show UI, but no devices to show");
        }

        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);

        if (getService().mRequest.isSingleDevice()) {
            setContentView(R.layout.device_confirmation);
            final DeviceFilterPair selectedDevice = getService().mDevicesFound.get(0);
            setTitle(Html.fromHtml(getString(
                    R.string.confirmation_title,
                    Html.escapeHtml(getCallingAppName()),
                    Html.escapeHtml(selectedDevice.getDisplayName())), 0));
            mPairButton = findViewById(R.id.button_pair);
            mPairButton.setOnClickListener(v -> onDeviceConfirmed(getService().mSelectedDevice));
            getService().mSelectedDevice = selectedDevice;
            onSelectionUpdate();
        } else {
            setContentView(R.layout.device_chooser);
            mPairButton = findViewById(R.id.button_pair);
            mPairButton.setVisibility(View.GONE);
            setTitle(Html.fromHtml(getString(R.string.chooser_title,
                    Html.escapeHtml(getCallingAppName())), 0));
            mDeviceListView = findViewById(R.id.device_list);
            final DeviceDiscoveryService.DevicesAdapter adapter = getService().mDevicesAdapter;
            mDeviceListView.setAdapter(adapter);
            adapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    onSelectionUpdate();
                }
            });
            mDeviceListView.addFooterView(mLoadingIndicator = getProgressBar(), null, false);
        }
        getService().mActivity = this;

        mCancelButton = findViewById(R.id.button_cancel);
        mCancelButton.setOnClickListener(v -> cancel());
    }

    private void cancel() {
        getService().onCancel();
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing() && !isChangingConfigurations()) {
            cancel();
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
        return requireNonNull(getService().mRequest.getCallingPackage());
    }

    @Override
    public void setTitle(CharSequence title) {
        final TextView titleView = findViewById(R.id.title);
        final int padding = getPadding(getResources());
        titleView.setPadding(padding, padding, padding, padding);
        titleView.setText(title);
    }

    //TODO put in resources xmls
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

    private DeviceDiscoveryService getService() {
        return DeviceDiscoveryService.sInstance;
    }

   protected void onDeviceConfirmed(DeviceFilterPair selectedDevice) {
        getService().onDeviceSelected(
                getCallingPackage(), getDeviceMacAddress(selectedDevice.device));
        setResult(RESULT_OK,
                new Intent().putExtra(CompanionDeviceManager.EXTRA_DEVICE, selectedDevice.device));
        finish();
    }
}