/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.packageinstaller.television;

import android.annotation.Nullable;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.android.packageinstaller.PackageUtil;
import com.android.packageinstaller.R;

public class UninstallAppProgressFragment extends Fragment implements View.OnClickListener,
        UninstallAppProgress.ProgressFragment {
    private static final String TAG = "UninstallAppProgressF"; // full class name is too long

    private Button mOkButton;
    private Button mDeviceManagerButton;
    private Button mUsersButton;

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        final View root = inflater.inflate(R.layout.uninstall_progress, container, false);
        // Initialize views
        View snippetView = root.findViewById(R.id.app_snippet);
        PackageUtil.initSnippetForInstalledApp(getContext(),
                ((UninstallAppProgress)getActivity()).getAppInfo(), snippetView);
        mDeviceManagerButton = (Button) root.findViewById(R.id.device_manager_button);
        mUsersButton = (Button) root.findViewById(R.id.users_button);
        mDeviceManagerButton.setVisibility(View.GONE);
        mDeviceManagerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent();
                intent.setClassName("com.android.settings",
                        "com.android.settings.Settings$DeviceAdminSettingsActivity");
                intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                getActivity().finish();
            }
        });
        mUsersButton.setVisibility(View.GONE);
        mUsersButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Settings.ACTION_USER_SETTINGS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                getActivity().finish();
            }
        });
        // Hide button till progress is being displayed
        mOkButton = (Button) root.findViewById(R.id.ok_button);
        mOkButton.setOnClickListener(this);

        return root;
    }

    public void onClick(View v) {
        final UninstallAppProgress activity = (UninstallAppProgress) getActivity();
        if(v == mOkButton && activity != null) {
            Log.i(TAG, "Finished uninstalling pkg: " +
                    activity.getAppInfo().packageName);
            activity.setResultAndFinish();
        }
    }

    @Override
    public void setUsersButtonVisible(boolean visible) {
        mUsersButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void setDeviceManagerButtonVisible(boolean visible) {
        mDeviceManagerButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void showCompletion(CharSequence statusText) {
        final View root = getView();
        root.findViewById(R.id.progress_view).setVisibility(View.GONE);
        root.findViewById(R.id.status_view).setVisibility(View.VISIBLE);
        ((TextView) root.findViewById(R.id.status_text)).setText(statusText);
        root.findViewById(R.id.ok_panel).setVisibility(View.VISIBLE);
    }
}
