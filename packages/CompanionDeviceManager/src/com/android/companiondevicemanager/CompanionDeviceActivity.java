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
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static com.android.companiondevicemanager.CompanionDeviceDiscoveryService.DiscoveryState;
import static com.android.companiondevicemanager.CompanionDeviceDiscoveryService.DiscoveryState.FINISHED_TIMEOUT;
import static com.android.companiondevicemanager.Utils.getApplicationLabel;
import static com.android.companiondevicemanager.Utils.getHtmlFromResources;
import static com.android.companiondevicemanager.Utils.getVendorHeaderIcon;
import static com.android.companiondevicemanager.Utils.getVendorHeaderName;
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
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

    // Activity result: Internal Error.
    private static final int RESULT_INTERNAL_ERROR = 2;

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

    // Only present for selfManaged devices.
    private ImageView mVendorHeaderImage;
    private TextView mVendorHeaderName;
    private ImageButton mVendorHeaderButton;

    // Progress indicator is only shown while we are looking for the first suitable device for a
    // "regular" (ie. not self-managed) association.
    private View mProgressIndicator;

    // Present for self-managed association requests and "single-device" regular association
    // regular.
    private Button mButtonAllow;
    // Present for all associations.
    private Button mButtonNotAllow;

    private LinearLayout mAssociationConfirmationDialog;
    private RelativeLayout mVendorHeader;

    // The recycler view is only shown for multiple-device regular association request, after
    // at least one matching device is found.
    private @Nullable RecyclerView mRecyclerView;
    private @Nullable DeviceListAdapter mAdapter;

    // The flag used to prevent double taps, that may lead to sending several requests for creating
    // an association to CDM.
    private boolean mApproved;
    private boolean mCancelled;
    // A reference to the device selected by the user, to be sent back to the application via
    // onActivityResult() after the association is created.
    private @Nullable DeviceFilterPair<?> mSelectedDevice;

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
            cancel(false); // will finish()
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

        mAssociationConfirmationDialog = findViewById(R.id.activity_confirmation);
        mVendorHeader = findViewById(R.id.vendor_header);

        mTitle = findViewById(R.id.title);
        mSummary = findViewById(R.id.summary);

        mVendorHeaderImage = findViewById(R.id.vendor_header_image);
        mVendorHeaderName = findViewById(R.id.vendor_header_name);
        mVendorHeaderButton = findViewById(R.id.vendor_header_button);

        mRecyclerView = findViewById(R.id.device_list);
        mAdapter = new DeviceListAdapter(this, this::onListItemClick);

        mButtonAllow = findViewById(R.id.btn_positive);
        mButtonNotAllow = findViewById(R.id.btn_negative);

        mButtonAllow.setOnClickListener(this::onPositiveButtonClick);
        mButtonNotAllow.setOnClickListener(this::onNegativeButtonClick);
        mVendorHeaderButton.setOnClickListener(this::onShowHelperDialog);

        if (mRequest.isSelfManaged()) {
            initUiForSelfManagedAssociation(appLabel);
        } else if (mRequest.isSingleDevice()) {
            initUiForSingleDevice(appLabel);
        } else {
            initUiForMultipleDevices(appLabel);
        }
    }

    private void onDiscoveryStateChanged(DiscoveryState newState) {
        if (newState == FINISHED_TIMEOUT
                && CompanionDeviceDiscoveryService.getScanResult().getValue().isEmpty()) {
            cancel(true);
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

    private void cancel(boolean discoveryTimeout) {
        if (DEBUG) {
            Log.i(TAG, "cancel(), discoveryTimeout=" + discoveryTimeout,
                    new Exception("Stack Trace Dump"));
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

        // First send callback to the app directly...
        try {
            mAppCallback.onFailure(discoveryTimeout ? "Timeout." : "Cancelled.");
        } catch (RemoteException ignore) {
        }

        // ... then set result and finish ("sending" onActivityResult()).
        setResultAndFinish(null, RESULT_CANCELED);
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

    private void initUiForSelfManagedAssociation(CharSequence appLabel) {
        if (DEBUG) Log.i(TAG, "initUiFor_SelfManaged_Association()");

        final CharSequence deviceName = mRequest.getDisplayName();
        final String deviceProfile = mRequest.getDeviceProfile();
        final String packageName = mRequest.getPackageName();
        final int userId = mRequest.getUserId();
        final Drawable vendorIcon;
        final CharSequence vendorName;
        final Spanned title;
        final Spanned summary;

        try {
            vendorIcon = getVendorHeaderIcon(this, packageName, userId);
            vendorName = getVendorHeaderName(this, packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package u" + userId + "/" + packageName + " not found.");
            setResultAndFinish(null, RESULT_INTERNAL_ERROR);
            return;
        }

        switch (deviceProfile) {
            case DEVICE_PROFILE_APP_STREAMING:
                title = getHtmlFromResources(this, R.string.title_app_streaming, appLabel);
                summary = getHtmlFromResources(
                        this, R.string.summary_app_streaming, appLabel, deviceName);
                break;

            case DEVICE_PROFILE_AUTOMOTIVE_PROJECTION:
                title = getHtmlFromResources(this, R.string.title_automotive_projection, appLabel);
                summary = getHtmlFromResources(
                        this, R.string.summary_automotive_projection, appLabel, deviceName);
                break;

            case DEVICE_PROFILE_COMPUTER:
                title = getHtmlFromResources(this, R.string.title_computer, appLabel);
                summary = getHtmlFromResources(
                        this, R.string.summary_computer, appLabel, deviceName);
                break;

            default:
                throw new RuntimeException("Unsupported profile " + deviceProfile);
        }

        mTitle.setText(title);
        mSummary.setText(summary);
        mVendorHeaderImage.setImageDrawable(vendorIcon);
        mVendorHeaderName.setText(vendorName);

        mRecyclerView.setVisibility(View.GONE);
        mVendorHeader.setVisibility(View.VISIBLE);
    }

    private void initUiForSingleDevice(CharSequence appLabel) {
        if (DEBUG) Log.i(TAG, "initUiFor_SingleDevice()");

        final String deviceProfile = mRequest.getDeviceProfile();

        CompanionDeviceDiscoveryService.getScanResult().observe(this,
                deviceFilterPairs -> updateSingleDeviceUi(
                        deviceFilterPairs, deviceProfile, appLabel));

        mRecyclerView.setVisibility(View.GONE);
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

        if (deviceProfile == null) {
            summary = getHtmlFromResources(this, R.string.summary_generic);
        } else if (deviceProfile.equals(DEVICE_PROFILE_WATCH)) {
            summary = getHtmlFromResources(this, R.string.summary_watch, appLabel, deviceName);
        } else {
            throw new RuntimeException("Unsupported profile " + deviceProfile);
        }

        mTitle.setText(title);
        mSummary.setText(summary);
    }

    private void initUiForMultipleDevices(CharSequence appLabel) {
        if (DEBUG) Log.i(TAG, "initUiFor_MultipleDevices()");

        final String deviceProfile = mRequest.getDeviceProfile();

        final String profileName;
        final Spanned summary;
        if (deviceProfile == null) {
            profileName = getString(R.string.profile_name_generic);
            summary = getHtmlFromResources(this, R.string.summary_generic);
        } else if (deviceProfile.equals(DEVICE_PROFILE_WATCH)) {
            profileName = getString(R.string.profile_name_watch);
            summary = getHtmlFromResources(this, R.string.summary_watch, appLabel);
        } else {
            throw new RuntimeException("Unsupported profile " + deviceProfile);
        }
        final Spanned title = getHtmlFromResources(
                this, R.string.chooser_title, profileName, appLabel);

        mTitle.setText(title);
        mSummary.setText(summary);

        mAdapter = new DeviceListAdapter(this, this::onListItemClick);

        // TODO: hide the list and show a spinner until a first device matching device is found.
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        CompanionDeviceDiscoveryService.getScanResult().observe(
                /* lifecycleOwner */ this,
                /* observer */ mAdapter);

        // "Remove" consent button: users would need to click on the list item.
        mButtonAllow.setVisibility(View.GONE);
    }

    private void onListItemClick(int position) {
        if (DEBUG) Log.d(TAG, "onListItemClick() " + position);

        final DeviceFilterPair<?> selectedDevice = mAdapter.getItem(position);

        if (mSelectedDevice != null) {
            if (DEBUG) Log.w(TAG, "Already selected.");
            return;
        }
        // Notify the adapter to highlight the selected item.
        mAdapter.setSelectedPosition(position);

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

        cancel(false);
    }

    private void onShowHelperDialog(View view) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        CompanionVendorHelperDialogFragment fragmentDialog =
                CompanionVendorHelperDialogFragment.newInstance(mRequest.getPackageName(),
                        mRequest.getUserId(), mRequest.getDeviceProfile());

        mAssociationConfirmationDialog.setVisibility(View.GONE);

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
