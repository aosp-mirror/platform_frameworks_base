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

package com.android.companiondevicemanager;

import static com.android.companiondevicemanager.CompanionDeviceResources.PROFILE_HELPER_SUMMARIES;
import static com.android.companiondevicemanager.Utils.getApplicationIcon;
import static com.android.companiondevicemanager.Utils.getApplicationLabel;
import static com.android.companiondevicemanager.Utils.getHtmlFromResources;

import android.annotation.Nullable;
import android.companion.AssociationRequest;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Spanned;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

/**
 * A fragmentDialog shows additional information about selfManaged devices
 */
public class CompanionVendorHelperDialogFragment extends DialogFragment {
    private static final String TAG = "CDM_CompanionVendorHelperDialogFragment";
    private static final String ASSOCIATION_REQUEST_EXTRA = "association_request";

    private CompanionVendorHelperDialogListener mListener;
    // Only present for selfManaged devices.
    private TextView mTitle;
    private TextView mSummary;
    private ImageView mAppIcon;
    private Button mButton;

    interface CompanionVendorHelperDialogListener {
        void onShowHelperDialogFailed(CharSequence error);
        void onHelperDialogDismissed();
    }

    private CompanionVendorHelperDialogFragment() {}

    static CompanionVendorHelperDialogFragment newInstance(AssociationRequest request) {
        CompanionVendorHelperDialogFragment fragmentDialog =
                new CompanionVendorHelperDialogFragment();

        Bundle bundle = new Bundle();
        bundle.putParcelable(ASSOCIATION_REQUEST_EXTRA, request);
        fragmentDialog.setArguments(bundle);

        return fragmentDialog;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mListener = (CompanionVendorHelperDialogListener) getActivity();
        // Hide the title bar in the dialog.
        setStyle(STYLE_NO_TITLE, /* Theme */0);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.helper_confirmation, container);
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        mListener.onHelperDialogDismissed();
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Drawable applicationIcon;
        AssociationRequest request = getArguments().getParcelable(
                ASSOCIATION_REQUEST_EXTRA, AssociationRequest.class);

        final String deviceProfile = request.getDeviceProfile();
        final String packageName = request.getPackageName();
        final CharSequence deviceName = request.getDisplayName();
        final int userId = request.getUserId();
        final CharSequence appLabel;

        try {
            applicationIcon = getApplicationIcon(getContext(), packageName);
            appLabel = getApplicationLabel(getContext(), packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package u" + userId + "/" + packageName + " not found.");
            mListener.onShowHelperDialogFailed(e.getMessage());
            return;
        }

        mTitle = view.findViewById(R.id.helper_title);
        mSummary = view.findViewById(R.id.helper_summary);
        mAppIcon = view.findViewById(R.id.app_icon);
        mButton = view.findViewById(R.id.btn_back);

        if (PROFILE_HELPER_SUMMARIES.containsKey(deviceProfile)) {
            final int summaryResourceId = PROFILE_HELPER_SUMMARIES.get(deviceProfile);
            final Spanned summary = getHtmlFromResources(
                    getContext(), summaryResourceId, appLabel, deviceName,
                    getString(R.string.device_type));
            mSummary.setText(summary);
        } else {
            throw new RuntimeException("Unsupported profile " + deviceProfile);
        }

        mTitle.setText(appLabel);
        mAppIcon.setImageDrawable(applicationIcon);

        mButton.setOnClickListener(v -> {
            dismiss();
            mListener.onHelperDialogDismissed();
        });
    }
}
