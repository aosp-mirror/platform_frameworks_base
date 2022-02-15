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
import static com.android.companiondevicemanager.Utils.prepareResultReceiverForIpc;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.companion.IAssociationRequestCallback;
import android.content.Intent;
import android.net.MacAddress;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.text.Spanned;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 *  A CompanionDevice activity response for showing the available
 *  nearby devices to be associated with.
 */
public class CompanionDeviceActivity extends AppCompatActivity {
    private static final boolean DEBUG = false;
    private static final String TAG = CompanionDeviceActivity.class.getSimpleName();

    // Keep the following constants in sync with
    // frameworks/base/services/companion/java/
    // com/android/server/companion/AssociationRequestsProcessor.java

    // AssociationRequestsProcessor <-> UI
    private static final String EXTRA_APPLICATION_CALLBACK = "application_callback";
    private static final String EXTRA_ASSOCIATION_REQUEST = "association_request";
    private static final String EXTRA_RESULT_RECEIVER = "result_receiver";

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

    // Progress indicator is only shown while we are looking for the first suitable device for a
    // "regular" (ie. not self-managed) association.
    private View mProgressIndicator;

    // Present for self-managed association requests and "single-device" regular association
    // regular.
    private Button mButtonAllow;

    // The list is only shown for multiple-device regular association request, after at least one
    // matching device is found.
    private @Nullable ListView mListView;
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

        setContentView(R.layout.activity_confirmation);

        mTitle = findViewById(R.id.title);
        mSummary = findViewById(R.id.summary);

        mListView = findViewById(R.id.device_list);
        mListView.setOnItemClickListener((av, iv, position, id) -> onListItemClick(position));

        mButtonAllow = findViewById(R.id.btn_positive);
        mButtonAllow.setOnClickListener(this::onPositiveButtonClick);
        findViewById(R.id.btn_negative).setOnClickListener(this::onNegativeButtonClick);

        final CharSequence appLabel = getApplicationLabel(this, mRequest.getPackageName());
        if (mRequest.isSelfManaged()) {
            initUiForSelfManagedAssociation(appLabel);
        } else if (mRequest.isSingleDevice()) {
            // TODO(b/211722613)
            // Treat singleDevice as the multipleDevices for now
            // initUiForSingleDevice(appLabel);
            initUiForMultipleDevices(appLabel);
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
        if (mSelectedDevice != null) {
            if (DEBUG) Log.w(TAG, "Already selected.");
            return;
        }
        mSelectedDevice = requireNonNull(selectedDevice);

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
        setResultAndFinish(association);
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
        setResultAndFinish(null);
    }

    private void setResultAndFinish(@Nullable AssociationInfo association) {
        if (DEBUG) Log.i(TAG, "setResultAndFinish(), association=" + association);

        final Intent data = new Intent();
        if (association != null) {
            data.putExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, association);
            if (!association.isSelfManaged()) {
                data.putExtra(CompanionDeviceManager.EXTRA_DEVICE, mSelectedDevice.getDevice());
            }
        }
        setResult(association != null ? RESULT_OK : RESULT_CANCELED, data);

        finish();
    }

    private void initUiForSelfManagedAssociation(CharSequence appLabel) {
        if (DEBUG) Log.i(TAG, "initUiFor_SelfManaged_Association()");

        final CharSequence deviceName = mRequest.getDisplayName(); // "<device>";
        final String deviceProfile = mRequest.getDeviceProfile(); // DEVICE_PROFILE_APP_STREAMING;

        final Spanned title;
        final Spanned summary;
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

        mListView.setVisibility(View.GONE);
    }

    private void initUiForSingleDevice(CharSequence appLabel) {
        if (DEBUG) Log.i(TAG, "initUiFor_SingleDevice()");

        // TODO: use real name
        final String deviceName = "<device>";
        final String deviceProfile = mRequest.getDeviceProfile();

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

        mListView.setVisibility(View.GONE);
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

        mAdapter = new DeviceListAdapter(this);

        // TODO: hide the list and show a spinner until a first device matching device is found.
        mListView.setAdapter(mAdapter);
        CompanionDeviceDiscoveryService.getScanResult().observe(
                /* lifecycleOwner */ this,
                /* observer */ mAdapter);

        // "Remove" consent button: users would need to click on the list item.
        mButtonAllow.setVisibility(View.GONE);
    }

    private void onListItemClick(int position) {
        if (DEBUG) Log.d(TAG, "onListItemClick() " + position);

        final DeviceFilterPair<?> selectedDevice = mAdapter.getItem(position);
        onUserSelectedDevice(selectedDevice);
    }

    private void onPositiveButtonClick(View v) {
        if (DEBUG) Log.d(TAG, "on_Positive_ButtonClick()");

        // Disable the button, to prevent more clicks.
        v.setEnabled(false);

        if (mRequest.isSelfManaged()) {
            onAssociationApproved(null);
        } else {
            // TODO(b/211722613): call onUserSelectedDevice().
            throw new UnsupportedOperationException(
                    "isSingleDevice() requests are not supported yet.");
        }
    }

    private void onNegativeButtonClick(View v) {
        if (DEBUG) Log.d(TAG, "on_Negative_ButtonClick()");

        // Disable the button, to prevent more clicks.
        v.setEnabled(false);

        cancel(false);
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
}
