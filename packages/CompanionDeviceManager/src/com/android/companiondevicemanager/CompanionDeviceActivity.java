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

import static android.companion.AssociationRequest.DEVICE_PROFILE_APP_STREAMING;
import static android.companion.AssociationRequest.DEVICE_PROFILE_AUTOMOTIVE_PROJECTION;
import static android.companion.AssociationRequest.DEVICE_PROFILE_COMPUTER;
import static android.companion.AssociationRequest.DEVICE_PROFILE_WATCH;
import static android.companion.CompanionDeviceManager.REASON_CANCELED;
import static android.companion.CompanionDeviceManager.REASON_DISCOVERY_TIMEOUT;
import static android.companion.CompanionDeviceManager.REASON_USER_REJECTED;
import static android.companion.CompanionDeviceManager.RESULT_DISCOVERY_TIMEOUT;
import static android.companion.CompanionDeviceManager.RESULT_INTERNAL_ERROR;
import static android.companion.CompanionDeviceManager.RESULT_USER_REJECTED;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static com.android.companiondevicemanager.CompanionDeviceDiscoveryService.DiscoveryState;
import static com.android.companiondevicemanager.CompanionDeviceDiscoveryService.DiscoveryState.FINISHED_TIMEOUT;
import static com.android.companiondevicemanager.PermissionListAdapter.TYPE_APPS;
import static com.android.companiondevicemanager.PermissionListAdapter.TYPE_NOTIFICATION;
import static com.android.companiondevicemanager.PermissionListAdapter.TYPE_STORAGE;
import static com.android.companiondevicemanager.Utils.getApplicationLabel;
import static com.android.companiondevicemanager.Utils.getHtmlFromResources;
import static com.android.companiondevicemanager.Utils.getIcon;
import static com.android.companiondevicemanager.Utils.getVendorHeaderIcon;
import static com.android.companiondevicemanager.Utils.getVendorHeaderName;
import static com.android.companiondevicemanager.Utils.hasVendorIcon;
import static com.android.companiondevicemanager.Utils.prepareResultReceiverForIpc;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.companion.IAssociationRequestCallback;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.MacAddress;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.text.Spanned;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 *  A CompanionDevice activity response for showing the available
 *  nearby devices to be associated with.
 */
public class CompanionDeviceActivity extends FragmentActivity implements
        CompanionVendorHelperDialogFragment.CompanionVendorHelperDialogListener {
    private static final boolean DEBUG = false;
    private static final String TAG = CompanionDeviceActivity.class.getSimpleName();

    // Keep the following constants in sync with
    // frameworks/base/services/companion/java/
    // com/android/server/companion/AssociationRequestsProcessor.java

    // AssociationRequestsProcessor <-> UI
    private static final String EXTRA_APPLICATION_CALLBACK = "application_callback";
    private static final String EXTRA_ASSOCIATION_REQUEST = "association_request";
    private static final String EXTRA_RESULT_RECEIVER = "result_receiver";

    private static final String FRAGMENT_DIALOG_TAG = "fragment_dialog";

    // AssociationRequestsProcessor -> UI
    private static final int RESULT_CODE_ASSOCIATION_CREATED = 0;
    private static final String EXTRA_ASSOCIATION = "association";

    // UI -> AssociationRequestsProcessor
    private static final int RESULT_CODE_ASSOCIATION_APPROVED = 0;
    private static final String EXTRA_MAC_ADDRESS = "mac_address";

    private AssociationRequest mRequest;
    private IAssociationRequestCallback mAppCallback;
    private ResultReceiver mCdmServiceReceiver;

    // Always present widgets.
    private TextView mTitle;
    private TextView mSummary;

    // Present for single device and multiple device only.
    private ImageView mProfileIcon;

    // Only present for selfManaged devices.
    private ImageView mVendorHeaderImage;
    private TextView mVendorHeaderName;
    private ImageButton mVendorHeaderButton;

    // Progress indicator is only shown while we are looking for the first suitable device for a
    // multiple device association.
    private ProgressBar mMultipleDeviceSpinner;
    // Progress indicator is only shown while we are looking for the first suitable device for a
    // single device association.
    private ProgressBar mSingleDeviceSpinner;

    // Present for self-managed association requests and "single-device" regular association
    // regular.
    private Button mButtonAllow;
    private Button mButtonNotAllow;
    // Present for multiple devices' association requests only.
    private Button mButtonNotAllowMultipleDevices;

    private LinearLayout mAssociationConfirmationDialog;
    private LinearLayout mMultipleDeviceList;
    private RelativeLayout mVendorHeader;

    // The recycler view is only shown for multiple-device regular association request, after
    // at least one matching device is found.
    private @Nullable RecyclerView mDeviceListRecyclerView;
    private @Nullable DeviceListAdapter mDeviceAdapter;


    // The recycler view is only shown for selfManaged association request.
    private @Nullable RecyclerView mPermissionListRecyclerView;
    private @Nullable PermissionListAdapter mPermissionListAdapter;

    // The flag used to prevent double taps, that may lead to sending several requests for creating
    // an association to CDM.
    private boolean mApproved;
    private boolean mCancelled;
    // A reference to the device selected by the user, to be sent back to the application via
    // onActivityResult() after the association is created.
    private @Nullable DeviceFilterPair<?> mSelectedDevice;

    private @Nullable List<Integer> mPermissionTypes;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (DEBUG) Log.d(TAG, "onCreate()");

        super.onCreate(savedInstanceState);
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (DEBUG) Log.d(TAG, "onStart()");

        final Intent intent = getIntent();
        mRequest = intent.getParcelableExtra(EXTRA_ASSOCIATION_REQUEST);
        mAppCallback = IAssociationRequestCallback.Stub.asInterface(
                intent.getExtras().getBinder(EXTRA_APPLICATION_CALLBACK));
        mCdmServiceReceiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);

        requireNonNull(mRequest);
        requireNonNull(mAppCallback);
        requireNonNull(mCdmServiceReceiver);

        // Start discovery services if needed.
        if (!mRequest.isSelfManaged()) {
            CompanionDeviceDiscoveryService.startForRequest(this, mRequest);
            // TODO(b/217749191): Create the ViewModel for the LiveData
            CompanionDeviceDiscoveryService.getDiscoveryState().observe(
                    /* LifeCycleOwner */ this, this::onDiscoveryStateChanged);
        }
        // Init UI.
        initUI();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // Handle another incoming request (while we are not done with the original - mRequest -
        // yet).
        final AssociationRequest request = requireNonNull(
                intent.getParcelableExtra(EXTRA_ASSOCIATION_REQUEST));
        if (DEBUG) Log.d(TAG, "onNewIntent(), request=" + request);

        // We can only "process" one request at a time.
        final IAssociationRequestCallback appCallback = IAssociationRequestCallback.Stub
                .asInterface(intent.getExtras().getBinder(EXTRA_APPLICATION_CALLBACK));
        try {
            requireNonNull(appCallback).onFailure("Busy.");
        } catch (RemoteException ignore) {
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (DEBUG) Log.d(TAG, "onStop(), finishing=" + isFinishing());

        // TODO: handle config changes without cancelling.
        if (!isDone()) {
            cancel(/* discoveryTimeOut */ false, /* userRejected */ false); // will finish()
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (DEBUG) Log.d(TAG, "onDestroy()");
    }

    @Override
    public void onBackPressed() {
        if (DEBUG) Log.d(TAG, "onBackPressed()");
        super.onBackPressed();
    }

    @Override
    public void finish() {
        if (DEBUG) Log.d(TAG, "finish()", new Exception("Stack Trace Dump"));
        super.finish();
    }

    private void initUI() {
        if (DEBUG) Log.d(TAG, "initUI(), request=" + mRequest);

        final String packageName = mRequest.getPackageName();
        final int userId = mRequest.getUserId();
        final CharSequence appLabel;

        try {
            appLabel = getApplicationLabel(this, packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Package u" + userId + "/" + packageName + " not found.");

            CompanionDeviceDiscoveryService.stop(this);
            setResultAndFinish(null, RESULT_INTERNAL_ERROR);
            return;
        }

        setContentView(R.layout.activity_confirmation);

        mMultipleDeviceList = findViewById(R.id.multiple_device_list);
        mAssociationConfirmationDialog = findViewById(R.id.association_confirmation);
        mVendorHeader = findViewById(R.id.vendor_header);

        mTitle = findViewById(R.id.title);
        mSummary = findViewById(R.id.summary);

        mProfileIcon = findViewById(R.id.profile_icon);

        mVendorHeaderImage = findViewById(R.id.vendor_header_image);
        mVendorHeaderName = findViewById(R.id.vendor_header_name);
        mVendorHeaderButton = findViewById(R.id.vendor_header_button);

        mDeviceListRecyclerView = findViewById(R.id.device_list);

        mMultipleDeviceSpinner = findViewById(R.id.spinner_multiple_device);
        mSingleDeviceSpinner = findViewById(R.id.spinner_single_device);
        mDeviceAdapter = new DeviceListAdapter(this, this::onListItemClick);

        mPermissionListRecyclerView = findViewById(R.id.permission_list);
        mPermissionListAdapter = new PermissionListAdapter(this);

        mButtonAllow = findViewById(R.id.btn_positive);
        mButtonNotAllow = findViewById(R.id.btn_negative);
        mButtonNotAllowMultipleDevices = findViewById(R.id.btn_negative_multiple_devices);

        mButtonAllow.setOnClickListener(this::onPositiveButtonClick);
        mButtonNotAllow.setOnClickListener(this::onNegativeButtonClick);
        mButtonNotAllowMultipleDevices.setOnClickListener(this::onNegativeButtonClick);

        mVendorHeaderButton.setOnClickListener(this::onShowHelperDialog);

        if (mRequest.isSelfManaged()) {
            initUiForSelfManagedAssociation();
        } else if (mRequest.isSingleDevice()) {
            initUiForSingleDevice(appLabel);
        } else {
            initUiForMultipleDevices(appLabel);
        }
    }

    private void onDiscoveryStateChanged(DiscoveryState newState) {
        if (newState == FINISHED_TIMEOUT
                && CompanionDeviceDiscoveryService.getScanResult().getValue().isEmpty()) {
            cancel(/* discoveryTimeOut */ true, /* userRejected */ false);
        }
    }

    private void onUserSelectedDevice(@NonNull DeviceFilterPair<?> selectedDevice) {
        final MacAddress macAddress = selectedDevice.getMacAddress();
        onAssociationApproved(macAddress);
    }

    private void onAssociationApproved(@Nullable MacAddress macAddress) {
        if (isDone()) {
            if (DEBUG) Log.w(TAG, "Already done: " + (mApproved ? "Approved" : "Cancelled"));
            return;
        }
        mApproved = true;

        if (DEBUG) Log.i(TAG, "onAssociationApproved() macAddress=" + macAddress);

        if (!mRequest.isSelfManaged()) {
            requireNonNull(macAddress);
            CompanionDeviceDiscoveryService.stop(this);
        }

        final Bundle data = new Bundle();
        data.putParcelable(EXTRA_ASSOCIATION_REQUEST, mRequest);
        data.putBinder(EXTRA_APPLICATION_CALLBACK, mAppCallback.asBinder());
        if (macAddress != null) {
            data.putParcelable(EXTRA_MAC_ADDRESS, macAddress);
        }

        data.putParcelable(EXTRA_RESULT_RECEIVER,
                prepareResultReceiverForIpc(mOnAssociationCreatedReceiver));

        mCdmServiceReceiver.send(RESULT_CODE_ASSOCIATION_APPROVED, data);
    }

    private void onAssociationCreated(@NonNull AssociationInfo association) {
        if (DEBUG) Log.i(TAG, "onAssociationCreated(), association=" + association);

        // Don't need to notify the app, CdmService has already done that. Just finish.
        setResultAndFinish(association, RESULT_OK);
    }

    private void cancel(boolean discoveryTimeout, boolean userRejected) {
        if (DEBUG) {
            Log.i(TAG, "cancel(), discoveryTimeout="
                    + discoveryTimeout
                    + ", userRejected="
                    + userRejected, new Exception("Stack Trace Dump"));
        }

        if (isDone()) {
            if (DEBUG) Log.w(TAG, "Already done: " + (mApproved ? "Approved" : "Cancelled"));
            return;
        }
        mCancelled = true;

        // Stop discovery service if it was used.
        if (!mRequest.isSelfManaged() || discoveryTimeout) {
            CompanionDeviceDiscoveryService.stop(this);
        }

        final String cancelReason;
        final int resultCode;
        if (userRejected) {
            cancelReason = REASON_USER_REJECTED;
            resultCode = RESULT_USER_REJECTED;
        } else if (discoveryTimeout) {
            cancelReason = REASON_DISCOVERY_TIMEOUT;
            resultCode = RESULT_DISCOVERY_TIMEOUT;
        } else {
            cancelReason = REASON_CANCELED;
            resultCode = RESULT_CANCELED;
        }

        // First send callback to the app directly...
        try {
            mAppCallback.onFailure(cancelReason);
        } catch (RemoteException ignore) {
        }

        // ... then set result and finish ("sending" onActivityResult()).
        setResultAndFinish(null, resultCode);
    }

    private void setResultAndFinish(@Nullable AssociationInfo association, int resultCode) {
        if (DEBUG) Log.i(TAG, "setResultAndFinish(), association=" + association);

        final Intent data = new Intent();
        if (association != null) {
            data.putExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, association);
            if (!association.isSelfManaged()) {
                data.putExtra(CompanionDeviceManager.EXTRA_DEVICE, mSelectedDevice.getDevice());
            }
        }
        setResult(resultCode, data);

        finish();
    }

    private void initUiForSelfManagedAssociation() {
        if (DEBUG) Log.i(TAG, "initUiFor_SelfManaged_Association()");

        final CharSequence deviceName = mRequest.getDisplayName();
        final String deviceProfile = mRequest.getDeviceProfile();
        final String packageName = mRequest.getPackageName();
        final int userId = mRequest.getUserId();
        final Drawable vendorIcon;
        final CharSequence vendorName;
        final Spanned title;

        mPermissionTypes = new ArrayList<>();

        try {
            vendorIcon = getVendorHeaderIcon(this, packageName, userId);
            vendorName = getVendorHeaderName(this, packageName, userId);

            mVendorHeaderImage.setImageDrawable(vendorIcon);
            if (hasVendorIcon(this, packageName, userId)) {
                mVendorHeaderImage.setColorFilter(getResources().getColor(
                                android.R.color.system_accent1_600, /* Theme= */null));
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package u" + userId + "/" + packageName + " not found.");
            setResultAndFinish(null, RESULT_INTERNAL_ERROR);
            return;
        }

        switch (deviceProfile) {
            case DEVICE_PROFILE_APP_STREAMING:
                title = getHtmlFromResources(this, R.string.title_app_streaming, deviceName);
                mPermissionTypes.add(TYPE_APPS);
                break;

            case DEVICE_PROFILE_AUTOMOTIVE_PROJECTION:
                title = getHtmlFromResources(
                        this, R.string.title_automotive_projection, deviceName);
                break;

            case DEVICE_PROFILE_COMPUTER:
                title = getHtmlFromResources(this, R.string.title_computer, deviceName);
                mPermissionTypes.add(TYPE_NOTIFICATION);
                mPermissionTypes.add(TYPE_STORAGE);
                break;

            default:
                throw new RuntimeException("Unsupported profile " + deviceProfile);
        }

        mSummary.setVisibility(View.GONE);

        mPermissionListAdapter.setPermissionType(mPermissionTypes);
        mPermissionListRecyclerView.setAdapter(mPermissionListAdapter);
        mPermissionListRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        mTitle.setText(title);
        mVendorHeaderName.setText(vendorName);
        mDeviceListRecyclerView.setVisibility(View.GONE);
        mProfileIcon.setVisibility(View.GONE);
        mVendorHeader.setVisibility(View.VISIBLE);
    }

    private void initUiForSingleDevice(CharSequence appLabel) {
        if (DEBUG) Log.i(TAG, "initUiFor_SingleDevice()");

        final String deviceProfile = mRequest.getDeviceProfile();

        CompanionDeviceDiscoveryService.getScanResult().observe(this,
                deviceFilterPairs -> updateSingleDeviceUi(
                        deviceFilterPairs, deviceProfile, appLabel));

        mSingleDeviceSpinner.setVisibility(View.VISIBLE);
        mPermissionListRecyclerView.setVisibility(View.GONE);
        mDeviceListRecyclerView.setVisibility(View.GONE);
        mAssociationConfirmationDialog.setVisibility(View.GONE);
    }

    private void updateSingleDeviceUi(List<DeviceFilterPair<?>> deviceFilterPairs,
            String deviceProfile, CharSequence appLabel) {
        // Ignore "empty" scan reports.
        if (deviceFilterPairs.isEmpty()) return;

        mSelectedDevice = requireNonNull(deviceFilterPairs.get(0));

        final String deviceName = mSelectedDevice.getDisplayName();
        final Spanned title = getHtmlFromResources(
                this, R.string.confirmation_title, appLabel, deviceName);
        final Spanned summary;
        final Drawable profileIcon;

        if (deviceProfile == null) {
            summary = getHtmlFromResources(this, R.string.summary_generic);
            profileIcon = getIcon(this, R.drawable.ic_device_other);
            mSummary.setVisibility(View.GONE);
        } else if (deviceProfile.equals(DEVICE_PROFILE_WATCH)) {
            summary = getHtmlFromResources(this, R.string.summary_watch, appLabel, deviceName);
            profileIcon = getIcon(this, R.drawable.ic_watch);
        } else {
            throw new RuntimeException("Unsupported profile " + deviceProfile);
        }

        mTitle.setText(title);
        mSummary.setText(summary);
        mProfileIcon.setImageDrawable(profileIcon);
        mSingleDeviceSpinner.setVisibility(View.GONE);
        mAssociationConfirmationDialog.setVisibility(View.VISIBLE);
    }

    private void initUiForMultipleDevices(CharSequence appLabel) {
        if (DEBUG) Log.i(TAG, "initUiFor_MultipleDevices()");

        final String deviceProfile = mRequest.getDeviceProfile();

        final String profileName;
        final Spanned summary;
        final Drawable profileIcon;
        if (deviceProfile == null) {
            profileName = getString(R.string.profile_name_generic);
            summary = getHtmlFromResources(this, R.string.summary_generic);
            profileIcon = getIcon(this, R.drawable.ic_device_other);
            mSummary.setVisibility(View.GONE);
        } else if (deviceProfile.equals(DEVICE_PROFILE_WATCH)) {
            profileName = getString(R.string.profile_name_watch);
            summary = getHtmlFromResources(this, R.string.summary_watch, appLabel);
            profileIcon = getIcon(this, R.drawable.ic_watch);
        } else {
            throw new RuntimeException("Unsupported profile " + deviceProfile);
        }
        final Spanned title = getHtmlFromResources(
                this, R.string.chooser_title, profileName, appLabel);

        mTitle.setText(title);
        mSummary.setText(summary);
        mProfileIcon.setImageDrawable(profileIcon);

        mDeviceListRecyclerView.setAdapter(mDeviceAdapter);
        mDeviceListRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        CompanionDeviceDiscoveryService.getScanResult().observe(this,
                deviceFilterPairs -> {
                    // Dismiss the progress bar once there's one device found for multiple devices.
                    if (deviceFilterPairs.size() >= 1) {
                        mMultipleDeviceSpinner.setVisibility(View.GONE);
                    }

                    mDeviceAdapter.setDevices(deviceFilterPairs);
                });

        // "Remove" consent button: users would need to click on the list item.
        mButtonAllow.setVisibility(View.GONE);
        mButtonNotAllow.setVisibility(View.GONE);
        mButtonNotAllowMultipleDevices.setVisibility(View.VISIBLE);
        mMultipleDeviceList.setVisibility(View.VISIBLE);
        mMultipleDeviceSpinner.setVisibility(View.VISIBLE);
    }

    private void onListItemClick(int position) {
        if (DEBUG) Log.d(TAG, "onListItemClick() " + position);

        final DeviceFilterPair<?> selectedDevice = mDeviceAdapter.getItem(position);

        if (mSelectedDevice != null) {
            if (DEBUG) Log.w(TAG, "Already selected.");
            return;
        }
        // Notify the adapter to highlight the selected item.
        mDeviceAdapter.setSelectedPosition(position);

        mSelectedDevice = requireNonNull(selectedDevice);

        onUserSelectedDevice(selectedDevice);
    }

    private void onPositiveButtonClick(View v) {
        if (DEBUG) Log.d(TAG, "on_Positive_ButtonClick()");

        // Disable the button, to prevent more clicks.
        v.setEnabled(false);

        if (mRequest.isSelfManaged()) {
            onAssociationApproved(null);
        } else {
            onUserSelectedDevice(mSelectedDevice);
        }
    }

    private void onNegativeButtonClick(View v) {
        if (DEBUG) Log.d(TAG, "on_Negative_ButtonClick()");

        // Disable the button, to prevent more clicks.
        v.setEnabled(false);

        cancel(/* discoveryTimeout */ false, /* userRejected */ true);
    }

    private void onShowHelperDialog(View view) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        CompanionVendorHelperDialogFragment fragmentDialog =
                CompanionVendorHelperDialogFragment.newInstance(mRequest);

        mAssociationConfirmationDialog.setVisibility(View.INVISIBLE);

        fragmentDialog.show(fragmentManager, /* Tag */ FRAGMENT_DIALOG_TAG);
    }

    private boolean isDone() {
        return mApproved || mCancelled;
    }

    private final ResultReceiver mOnAssociationCreatedReceiver =
            new ResultReceiver(Handler.getMain()) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle data) {
                    if (resultCode != RESULT_CODE_ASSOCIATION_CREATED) {
                        throw new RuntimeException("Unknown result code: " + resultCode);
                    }

                    final AssociationInfo association = data.getParcelable(EXTRA_ASSOCIATION);
                    requireNonNull(association);

                    onAssociationCreated(association);
                }
            };

    @Override
    public void onShowHelperDialogFailed() {
        setResultAndFinish(null, RESULT_INTERNAL_ERROR);
    }

    @Override
    public void onHelperDialogDismissed() {
        mAssociationConfirmationDialog.setVisibility(View.VISIBLE);
    }
}
