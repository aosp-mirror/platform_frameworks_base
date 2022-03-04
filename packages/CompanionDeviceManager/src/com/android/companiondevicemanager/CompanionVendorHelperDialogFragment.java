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

import static android.companion.AssociationRequest.DEVICE_PROFILE_APP_STREAMING;
import static android.companion.AssociationRequest.DEVICE_PROFILE_COMPUTER;

import static com.android.companiondevicemanager.Utils.getApplicationIcon;
import static com.android.companiondevicemanager.Utils.getHtmlFromResources;

import android.annotation.Nullable;
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
    private static final String TAG = CompanionVendorHelperDialogFragment.class.getSimpleName();

    private static final String PACKAGE_NAME_EXTRA = "packageName";
    private static final String DEVICE_PROFILE_EXTRA = "deviceProfile";
    private static final String USER_ID_EXTRA = "userId";

    private CompanionVendorHelperDialogListener mListener;
    // Only present for selfManaged devices.
    private TextView mTitle;
    private TextView mSummary;
    private ImageView mAppIcon;
    private Button mButton;

    interface CompanionVendorHelperDialogListener {
        void onShowHelperDialogFailed();
        void onHelperDialogDismissed();
    }

    private CompanionVendorHelperDialogFragment() {}

    static CompanionVendorHelperDialogFragment newInstance(String packageName,
            int userId, String deviceProfile) {
        CompanionVendorHelperDialogFragment fragmentDialog =
                new CompanionVendorHelperDialogFragment();

        Bundle bundle = new Bundle();
        bundle.putString(PACKAGE_NAME_EXTRA, packageName);
        bundle.putInt(USER_ID_EXTRA, userId);
        bundle.putString(DEVICE_PROFILE_EXTRA, deviceProfile);
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
        String packageName = getArguments().getString(PACKAGE_NAME_EXTRA);
        String deviceProfile = getArguments().getString(DEVICE_PROFILE_EXTRA);
        int userId = getArguments().getInt(USER_ID_EXTRA);

        try {
            applicationIcon = getApplicationIcon(getContext(), packageName);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package u" + userId + "/" + packageName + " not found.");
            mListener.onShowHelperDialogFailed();
            return;
        }

        mTitle = view.findViewById(R.id.helper_title);
        mSummary = view.findViewById(R.id.helper_summary);
        mAppIcon = view.findViewById(R.id.app_icon);
        mButton = view.findViewById(R.id.btn_ok);

        final Spanned title;
        final Spanned summary;

        switch (deviceProfile) {
            case DEVICE_PROFILE_APP_STREAMING:
                title = getHtmlFromResources(getContext(), R.string.helper_title_app_streaming);
                summary = getHtmlFromResources(getContext(), R.string.helper_summary_app_streaming);
                break;

            case DEVICE_PROFILE_COMPUTER:
                title = getHtmlFromResources(getContext(), R.string.helper_title_computer);
                summary = getHtmlFromResources(getContext(), R.string.helper_summary_computer);
                break;

            default:
                throw new RuntimeException("Unsupported profile " + deviceProfile);
        }

        mTitle.setText(title);
        mSummary.setText(summary);
        mAppIcon.setImageDrawable(applicationIcon);

        mButton.setOnClickListener(v -> {
            dismiss();
            mListener.onHelperDialogDismissed();
        });
    }
}
