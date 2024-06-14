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

import static android.companion.CompanionDeviceManager.REASON_CANCELED;
import static android.companion.CompanionDeviceManager.REASON_DISCOVERY_TIMEOUT;
import static android.companion.CompanionDeviceManager.REASON_INTERNAL_ERROR;
import static android.companion.CompanionDeviceManager.REASON_USER_REJECTED;
import static android.companion.CompanionDeviceManager.RESULT_DISCOVERY_TIMEOUT;
import static android.companion.CompanionDeviceManager.RESULT_INTERNAL_ERROR;
import static android.companion.CompanionDeviceManager.RESULT_USER_REJECTED;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static com.android.companiondevicemanager.CompanionDeviceDiscoveryService.DiscoveryState;
import static com.android.companiondevicemanager.CompanionDeviceDiscoveryService.DiscoveryState.FINISHED_TIMEOUT;
import static com.android.companiondevicemanager.CompanionDeviceDiscoveryService.LOCK;
import static com.android.companiondevicemanager.CompanionDeviceDiscoveryService.sDiscoveryStarted;
import static com.android.companiondevicemanager.CompanionDeviceResources.PROFILE_ICONS;
import static com.android.companiondevicemanager.CompanionDeviceResources.PROFILE_NAMES;
import static com.android.companiondevicemanager.CompanionDeviceResources.PROFILE_PERMISSIONS;
import static com.android.companiondevicemanager.CompanionDeviceResources.PROFILE_SUMMARIES;
import static com.android.companiondevicemanager.CompanionDeviceResources.PROFILE_TITLES;
import static com.android.companiondevicemanager.CompanionDeviceResources.SUPPORTED_PROFILES;
import static com.android.companiondevicemanager.CompanionDeviceResources.SUPPORTED_SELF_MANAGED_PROFILES;
import static com.android.companiondevicemanager.Utils.getApplicationLabel;
import static com.android.companiondevicemanager.Utils.getHtmlFromResources;
import static com.android.companiondevicemanager.Utils.getIcon;
import static com.android.companiondevicemanager.Utils.getImageColor;
import static com.android.companiondevicemanager.Utils.getVendorHeaderIcon;
import static com.android.companiondevicemanager.Utils.getVendorHeaderName;
import static com.android.companiondevicemanager.Utils.hasVendorIcon;
import static com.android.companiondevicemanager.Utils.prepareResultReceiverForIpc;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.companion.AssociatedDevice;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.companion.IAssociationRequestCallback;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.MacAddress;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.text.Spanned;
import android.util.Slog;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
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
@SuppressLint("LongLogTag")
public class CompanionAssociationActivity extends FragmentActivity implements
        CompanionVendorHelperDialogFragment.CompanionVendorHelperDialogListener {
    private static final String TAG = "CDM_CompanionDeviceActivity";

    // Keep the following constants in sync with
    // frameworks/base/services/companion/java/
    // com/android/server/companion/AssociationRequestsProcessor.java

    // AssociationRequestsProcessor <-> UI
    private static final String EXTRA_APPLICATION_CALLBACK = "application_callback";
    private static final String EXTRA_ASSOCIATION_REQUEST = "association_request";
    private static final String EXTRA_RESULT_RECEIVER = "result_receiver";
    private static final String EXTRA_FORCE_CANCEL_CONFIRMATION = "cancel_confirmation";

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

    // Present for application's name.
    private CharSequence mAppLabel;

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

    // Present for top and bottom borders for permissions list and device list.
    private View mBorderTop;
    private View mBorderBottom;

    private LinearLayout mAssociationConfirmationDialog;
    // Contains device list, permission list and top/bottom borders.
    private ConstraintLayout mConstraintList;
    // Only present for self-managed association requests.
    private RelativeLayout mVendorHeader;
    // A linearLayout for mButtonNotAllowMultipleDevices, user will press this layout instead
    // of the button for accessibility.
    private LinearLayout mNotAllowMultipleDevicesLayout;

    // The recycler view is only shown for multiple-device regular association request, after
    // at least one matching device is found.
    private @Nullable RecyclerView mDeviceListRecyclerView;
    private @Nullable DeviceListAdapter mDeviceAdapter;

    // The recycler view is shown for non-null profile association request.
    private @Nullable RecyclerView mPermissionListRecyclerView;
    private @Nullable PermissionListAdapter mPermissionListAdapter;

    // The flag used to prevent double taps, that may lead to sending several requests for creating
    // an association to CDM.
    private boolean mApproved;
    private boolean mCancelled;
    // A reference to the device selected by the user, to be sent back to the application via
    // onActivityResult() after the association is created.
    private @Nullable DeviceFilterPair<?> mSelectedDevice;

    private final LinearLayoutManager mPermissionsLayoutManager = new LinearLayoutManager(this);

    @Override
    public void onCreate(Bundle savedInstanceState) {
        boolean forceCancelDialog = getIntent().getBooleanExtra(EXTRA_FORCE_CANCEL_CONFIRMATION,
                false);
        // Must handle the force cancel request in onNewIntent.
        if (forceCancelDialog) {
            Slog.i(TAG, "The confirmation does not exist, skipping the cancel request");
            finish();
        }

        super.onCreate(savedInstanceState);
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
    }

    @Override
    protected void onStart() {
        super.onStart();

        final Intent intent = getIntent();
        mRequest = intent.getParcelableExtra(EXTRA_ASSOCIATION_REQUEST, AssociationRequest.class);
        mAppCallback = IAssociationRequestCallback.Stub.asInterface(
                intent.getExtras().getBinder(EXTRA_APPLICATION_CALLBACK));
        mCdmServiceReceiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER,
                ResultReceiver.class);

        requireNonNull(mRequest);
        requireNonNull(mAppCallback);
        requireNonNull(mCdmServiceReceiver);

        // Start discovery services if needed.
        if (!mRequest.isSelfManaged()) {
            boolean started = CompanionDeviceDiscoveryService.startForRequest(this, mRequest);
            if (!started) {
                return;
            }
            // TODO(b/217749191): Create the ViewModel for the LiveData
            CompanionDeviceDiscoveryService.getDiscoveryState().observe(
                    /* LifeCycleOwner */ this, this::onDiscoveryStateChanged);
        }
        // Init UI.
        initUI();
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);

        // Force cancels the CDM dialog if this activity receives another intent with
        // EXTRA_FORCE_CANCEL_CONFIRMATION.
        boolean forCancelDialog = intent.getBooleanExtra(EXTRA_FORCE_CANCEL_CONFIRMATION, false);
        if (forCancelDialog) {
            Slog.i(TAG, "Cancelling the user confirmation");
            cancel(/* discoveryTimeOut */ false, /* userRejected */ false,
                    /* internalError */ false);
            return;
        }

        // Handle another incoming request (while we are not done with the original - mRequest -
        // yet). We can only "process" one request at a time.
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

        // TODO: handle config changes without cancelling.
        if (!isDone()) {
            cancel(/* discoveryTimeOut */ false,
                    /* userRejected */ false, /* internalError */ false); // will finish()
        }
    }

    private void initUI() {
        Slog.d(TAG, "initUI(), request=" + mRequest);

        final String packageName = mRequest.getPackageName();
        final int userId = mRequest.getUserId();
        final CharSequence appLabel;

        try {
            appLabel = getApplicationLabel(this, packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Package u" + userId + "/" + packageName + " not found.");

            CompanionDeviceDiscoveryService.stop(this);
            setResultAndFinish(null, RESULT_INTERNAL_ERROR);
            return;
        }

        setContentView(R.layout.activity_confirmation);

        mAppLabel = appLabel;

        mConstraintList = findViewById(R.id.constraint_list);
        mAssociationConfirmationDialog = findViewById(R.id.association_confirmation);
        mVendorHeader = findViewById(R.id.vendor_header);

        mBorderTop = findViewById(R.id.border_top);
        mBorderBottom = findViewById(R.id.border_bottom);

        mTitle = findViewById(R.id.title);
        mSummary = findViewById(R.id.summary);

        mProfileIcon = findViewById(R.id.profile_icon);

        mVendorHeaderImage = findViewById(R.id.vendor_header_image);
        mVendorHeaderName = findViewById(R.id.vendor_header_name);
        mVendorHeaderButton = findViewById(R.id.vendor_header_button);

        mDeviceListRecyclerView = findViewById(R.id.device_list);

        mMultipleDeviceSpinner = findViewById(R.id.spinner_multiple_device);
        mSingleDeviceSpinner = findViewById(R.id.spinner_single_device);

        mPermissionListRecyclerView = findViewById(R.id.permission_list);
        mPermissionListAdapter = new PermissionListAdapter(this);

        mButtonAllow = findViewById(R.id.btn_positive);
        mButtonNotAllow = findViewById(R.id.btn_negative);
        mButtonNotAllowMultipleDevices = findViewById(R.id.btn_negative_multiple_devices);
        mNotAllowMultipleDevicesLayout = findViewById(R.id.negative_multiple_devices_layout);

        mButtonAllow.setOnClickListener(this::onPositiveButtonClick);
        mButtonNotAllow.setOnClickListener(this::onNegativeButtonClick);
        mNotAllowMultipleDevicesLayout.setOnClickListener(this::onNegativeButtonClick);

        mVendorHeaderButton.setOnClickListener(this::onShowHelperDialog);

        if (mRequest.isSelfManaged()) {
            initUiForSelfManagedAssociation();
        } else if (mRequest.isSingleDevice()) {
            initUiForSingleDevice();
        } else {
            initUiForMultipleDevices();
        }
    }

    private void onDiscoveryStateChanged(DiscoveryState newState) {
        if (newState == FINISHED_TIMEOUT
                && CompanionDeviceDiscoveryService.getScanResult().getValue().isEmpty()) {
            synchronized (LOCK) {
                if (sDiscoveryStarted) {
                    cancel(/* discoveryTimeOut */ true,
                            /* userRejected */ false, /* internalError */ false);
                }
            }
        }
    }

    private void onUserSelectedDevice(@NonNull DeviceFilterPair<?> selectedDevice) {
        final MacAddress macAddress = selectedDevice.getMacAddress();
        mRequest.setDisplayName(selectedDevice.getDisplayName());
        mRequest.setAssociatedDevice(new AssociatedDevice(selectedDevice.getDevice()));
        onAssociationApproved(macAddress);
    }

    private void onAssociationApproved(@Nullable MacAddress macAddress) {
        if (isDone()) {
            Slog.w(TAG, "Already done: " + (mApproved ? "Approved" : "Cancelled"));
            return;
        }
        mApproved = true;

        Slog.i(TAG, "onAssociationApproved() macAddress=" + macAddress);

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

    private void cancel(boolean discoveryTimeout, boolean userRejected, boolean internalError) {
        if (isDone()) {
            Slog.w(TAG, "Already done: " + (mApproved ? "Approved" : "Cancelled"));
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
        } else if (internalError) {
            cancelReason = REASON_INTERNAL_ERROR;
            resultCode = RESULT_INTERNAL_ERROR;
        } else {
            cancelReason = REASON_CANCELED;
            resultCode = CompanionDeviceManager.RESULT_CANCELED;
        }

        // First send callback to the app directly...
        try {
            Slog.i(TAG, "Sending onFailure to app due to reason=" + cancelReason);
            mAppCallback.onFailure(cancelReason);
        } catch (RemoteException ignore) {
        }

        // ... then set result and finish ("sending" onActivityResult()).
        setResultAndFinish(null, resultCode);
    }

    private void setResultAndFinish(@Nullable AssociationInfo association, int resultCode) {
        Slog.i(TAG, "setResultAndFinish(), association="
                + (association == null ? "null" : association)
                + "resultCode=" + resultCode);

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
        Slog.d(TAG, "initUiForSelfManagedAssociation()");

        final CharSequence deviceName = mRequest.getDisplayName();
        final String deviceProfile = mRequest.getDeviceProfile();
        final String packageName = mRequest.getPackageName();
        final int userId = mRequest.getUserId();
        final Drawable vendorIcon;
        final CharSequence vendorName;
        final Spanned title;

        if (!SUPPORTED_SELF_MANAGED_PROFILES.contains(deviceProfile)) {
            throw new RuntimeException("Unsupported profile " + deviceProfile);
        }

        try {
            vendorIcon = getVendorHeaderIcon(this, packageName, userId);
            vendorName = getVendorHeaderName(this, packageName, userId);
            mVendorHeaderImage.setImageDrawable(vendorIcon);
            if (hasVendorIcon(this, packageName, userId)) {
                int color = getImageColor(this);
                mVendorHeaderImage.setColorFilter(getResources().getColor(color, /* Theme= */null));
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Package u" + userId + "/" + packageName + " not found.");
            cancel(/* discoveryTimeout */ false,
                    /* userRejected */ false, /* internalError */ true);
            return;
        }

        title = getHtmlFromResources(this, PROFILE_TITLES.get(deviceProfile), deviceName);

        if (PROFILE_SUMMARIES.containsKey(deviceProfile)) {
            final int summaryResourceId = PROFILE_SUMMARIES.get(deviceProfile);
            final Spanned summary = getHtmlFromResources(this, summaryResourceId,
                    deviceName);
            mSummary.setText(summary);
        } else {
            mSummary.setVisibility(View.GONE);
        }

        setupPermissionList(deviceProfile);

        mTitle.setText(title);
        mVendorHeaderName.setText(vendorName);
        mVendorHeader.setVisibility(View.VISIBLE);
        mProfileIcon.setVisibility(View.GONE);
        mDeviceListRecyclerView.setVisibility(View.GONE);
        // Top and bottom borders should be gone for selfManaged dialog.
        mBorderTop.setVisibility(View.GONE);
        mBorderBottom.setVisibility(View.GONE);
    }

    private void initUiForSingleDevice() {
        Slog.d(TAG, "initUiForSingleDevice()");

        final String deviceProfile = mRequest.getDeviceProfile();

        if (!SUPPORTED_PROFILES.contains(deviceProfile)) {
            throw new RuntimeException("Unsupported profile " + deviceProfile);
        }

        final Drawable profileIcon = getIcon(this, PROFILE_ICONS.get(deviceProfile));
        mProfileIcon.setImageDrawable(profileIcon);

        CompanionDeviceDiscoveryService.getScanResult().observe(this, deviceFilterPairs -> {
            if (deviceFilterPairs.isEmpty()) {
                return;
            }
            mSelectedDevice = requireNonNull(deviceFilterPairs.get(0));
            updateSingleDeviceUi();
        });

        mSingleDeviceSpinner.setVisibility(View.VISIBLE);
        // Hide permission list and confirmation dialog first before the
        // first matched device is found.
        mPermissionListRecyclerView.setVisibility(View.GONE);
        mDeviceListRecyclerView.setVisibility(View.GONE);
        mAssociationConfirmationDialog.setVisibility(View.GONE);
    }

    private void initUiForMultipleDevices() {
        Slog.d(TAG, "initUiForMultipleDevices()");

        final Drawable profileIcon;
        final Spanned title;
        final String deviceProfile = mRequest.getDeviceProfile();

        if (!SUPPORTED_PROFILES.contains(deviceProfile)) {
            throw new RuntimeException("Unsupported profile " + deviceProfile);
        }

        profileIcon = getIcon(this, PROFILE_ICONS.get(deviceProfile));

        if (deviceProfile == null) {
            title = getHtmlFromResources(this, R.string.chooser_title_non_profile, mAppLabel);
            mButtonNotAllowMultipleDevices.setText(R.string.consent_no);
        } else {
            title = getHtmlFromResources(this,
                    R.string.chooser_title, getString(PROFILE_NAMES.get(deviceProfile)));
        }

        mDeviceAdapter = new DeviceListAdapter(this, this::onDeviceClicked);

        mTitle.setText(title);
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

        mSummary.setVisibility(View.GONE);
        // "Remove" consent button: users would need to click on the list item.
        mButtonAllow.setVisibility(View.GONE);
        mButtonNotAllow.setVisibility(View.GONE);
        mDeviceListRecyclerView.setVisibility(View.VISIBLE);
        mButtonNotAllowMultipleDevices.setVisibility(View.VISIBLE);
        mNotAllowMultipleDevicesLayout.setVisibility(View.VISIBLE);
        mConstraintList.setVisibility(View.VISIBLE);
        mMultipleDeviceSpinner.setVisibility(View.VISIBLE);
    }

    private void onDeviceClicked(int position) {
        final DeviceFilterPair<?> selectedDevice = mDeviceAdapter.getItem(position);
        // To prevent double tap on the selected device.
        if (mSelectedDevice != null) {
            Slog.w(TAG, "Already selected.");
            return;
        }
        // Notify the adapter to highlight the selected item.
        mDeviceAdapter.setSelectedPosition(position);

        mSelectedDevice = requireNonNull(selectedDevice);

        Slog.d(TAG, "onDeviceClicked(): " + mSelectedDevice.toShortString());

        updateSingleDeviceUi();

        mSummary.setVisibility(View.VISIBLE);
        mButtonAllow.setVisibility(View.VISIBLE);
        mButtonNotAllow.setVisibility(View.VISIBLE);
        mDeviceListRecyclerView.setVisibility(View.GONE);
        mNotAllowMultipleDevicesLayout.setVisibility(View.GONE);
    }

    private void updateSingleDeviceUi() {
        // No need to show permission consent dialog if it is a isSkipPrompt(true)
        // AssociationRequest. See AssociationRequestsProcessor#mayAssociateWithoutPrompt.
        if (mRequest.isSkipPrompt()) {
            Slog.d(TAG, "Skipping the permission consent dialog.");
            onUserSelectedDevice(mSelectedDevice);
            return;
        }

        mSingleDeviceSpinner.setVisibility(View.GONE);
        mAssociationConfirmationDialog.setVisibility(View.VISIBLE);

        final String deviceProfile = mRequest.getDeviceProfile();
        final int summaryResourceId = PROFILE_SUMMARIES.get(deviceProfile);
        final String remoteDeviceName = mSelectedDevice.getDisplayName();
        final Spanned title = getHtmlFromResources(
                this, PROFILE_TITLES.get(deviceProfile), mAppLabel, remoteDeviceName);
        final Spanned summary;

        if (deviceProfile == null && mRequest.isSingleDevice()) {
            summary = getHtmlFromResources(this, summaryResourceId, remoteDeviceName);
            mConstraintList.setVisibility(View.GONE);
        } else if (deviceProfile == null) {
            onUserSelectedDevice(mSelectedDevice);
            return;
        } else {
            summary = getHtmlFromResources(
                    this, summaryResourceId, getString(R.string.device_type));
            setupPermissionList(deviceProfile);
        }

        mTitle.setText(title);
        mSummary.setText(summary);
    }

    private void onPositiveButtonClick(View v) {
        Slog.d(TAG, "onPositiveButtonClick()");

        // Disable the button, to prevent more clicks.
        v.setEnabled(false);

        if (mRequest.isSelfManaged()) {
            onAssociationApproved(null);
        } else {
            onUserSelectedDevice(mSelectedDevice);
        }
    }

    private void onNegativeButtonClick(View v) {
        Slog.d(TAG, "onNegativeButtonClick()");

        // Disable the button, to prevent more clicks.
        v.setEnabled(false);

        cancel(/* discoveryTimeout */ false, /* userRejected */ true, /* internalError */ false);
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

    // Set up the mPermissionListRecyclerView, including set up the adapter,
    // initiate the layoutManager for the recyclerview, add listeners for monitoring the scrolling
    // and when mPermissionListRecyclerView is fully populated.
    // Lastly, disable the Allow and Don't allow buttons.
    private void setupPermissionList(String deviceProfile) {
        final List<Integer> permissionTypes = new ArrayList<>(
                PROFILE_PERMISSIONS.get(deviceProfile));
        if (permissionTypes.isEmpty()) {
            // Nothing to do if there are no permission types.
            return;
        }

        mPermissionListAdapter.setPermissionType(permissionTypes);
        mPermissionListRecyclerView.setAdapter(mPermissionListAdapter);
        mPermissionListRecyclerView.setLayoutManager(mPermissionsLayoutManager);

        disableButtons();

        LinearLayoutManager permissionListLayoutManager =
                (LinearLayoutManager) mPermissionListRecyclerView
                        .getLayoutManager();

        // Enable buttons once users scroll down to the bottom of the permission list.
        mPermissionListRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                enableAllowButtonIfNeeded(permissionListLayoutManager);
            }
        });
        // Enable buttons if last item in the permission list is visible to the users when
        // mPermissionListRecyclerView is fully populated.
        mPermissionListRecyclerView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        enableAllowButtonIfNeeded(permissionListLayoutManager);
                        mPermissionListRecyclerView.getViewTreeObserver()
                                .removeOnGlobalLayoutListener(this);
                    }
                });

        // Set accessibility for the recyclerView that to be able scroll up/down for voice access.
        mPermissionListRecyclerView.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
            }
        });

        mConstraintList.setVisibility(View.VISIBLE);
        mPermissionListRecyclerView.setVisibility(View.VISIBLE);
    }

    // Enable the Allow button if the last element in the PermissionListRecyclerView is reached.
    private void enableAllowButtonIfNeeded(LinearLayoutManager layoutManager) {
        int lastVisibleItemPosition =
                layoutManager.findLastCompletelyVisibleItemPosition();
        int numItems = mPermissionListRecyclerView.getAdapter().getItemCount();

        if (lastVisibleItemPosition >= numItems - 1) {
            enableButtons();
        }
    }

    // Disable and grey out the Allow and Don't allow buttons if the last permission in the
    // permission list is not visible to the users.
    private void disableButtons() {
        mButtonAllow.setEnabled(false);
        mButtonNotAllow.setEnabled(false);
        mButtonAllow.setTextColor(
                getResources().getColor(android.R.color.system_neutral1_400, null));
        mButtonNotAllow.setTextColor(
                getResources().getColor(android.R.color.system_neutral1_400, null));
        mButtonAllow.getBackground().setColorFilter(
                (new BlendModeColorFilter(Color.LTGRAY,  BlendMode.DARKEN)));
        mButtonNotAllow.getBackground().setColorFilter(
                (new BlendModeColorFilter(Color.LTGRAY,  BlendMode.DARKEN)));
    }
    // Enable and restore the color for the Allow and Don't allow buttons if the last permission in
    // the permission list is visible to the users.
    private void enableButtons() {
        mButtonAllow.setEnabled(true);
        mButtonNotAllow.setEnabled(true);
        mButtonAllow.getBackground().setColorFilter(null);
        mButtonNotAllow.getBackground().setColorFilter(null);
        mButtonAllow.setTextColor(
                getResources().getColor(android.R.color.system_neutral1_900, null));
        mButtonNotAllow.setTextColor(
                getResources().getColor(android.R.color.system_neutral1_900, null));
    }

    private final ResultReceiver mOnAssociationCreatedReceiver =
            new ResultReceiver(Handler.getMain()) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle data) {
                    if (resultCode == RESULT_CODE_ASSOCIATION_CREATED) {
                        final AssociationInfo association = data.getParcelable(
                                EXTRA_ASSOCIATION, AssociationInfo.class);
                        requireNonNull(association);
                        setResultAndFinish(association, CompanionDeviceManager.RESULT_OK);
                    } else {
                        setResultAndFinish(null, resultCode);
                    }
                }
            };

    @Override
    public void onShowHelperDialogFailed() {
        cancel(/* discoveryTimeout */ false, /* userRejected */ false, /* internalError */ true);
    }

    @Override
    public void onHelperDialogDismissed() {
        mAssociationConfirmationDialog.setVisibility(View.VISIBLE);
    }
}
