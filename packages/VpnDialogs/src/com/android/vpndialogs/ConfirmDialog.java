/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.vpndialogs;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.IConnectivityManager;
import android.net.VpnService;
import android.os.ServiceManager;
import android.text.Html;
import android.text.Html.ImageGetter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.net.VpnConfig;

import java.util.List;

public class ConfirmDialog extends AlertActivity
        implements DialogInterface.OnClickListener, ImageGetter {
    private static final String TAG = "VpnConfirm";

    private String mPackage;

    private IConnectivityManager mService;

    private Button mButton;

    @Override
    protected void onResume() {
        super.onResume();
        try {
            mPackage = getCallingPackage();

            mService = IConnectivityManager.Stub.asInterface(
                    ServiceManager.getService(Context.CONNECTIVITY_SERVICE));

            if (mService.prepareVpn(mPackage, null)) {
                setResult(RESULT_OK);
                finish();
                return;
            }

            View view = View.inflate(this, R.layout.confirm, null);

            ((TextView) view.findViewById(R.id.warning)).setText(
                    Html.fromHtml(
                            getString(R.string.warning, VpnConfig.getVpnLabel(this, mPackage)),
                    this, null /* tagHandler */));

            mAlertParams.mTitle = getText(R.string.prompt);
            mAlertParams.mPositiveButtonText = getText(android.R.string.ok);
            mAlertParams.mPositiveButtonListener = this;
            mAlertParams.mNegativeButtonText = getText(android.R.string.cancel);
            mAlertParams.mView = view;
            setupAlert();

            getWindow().setCloseOnTouchOutside(false);
            mButton = mAlert.getButton(DialogInterface.BUTTON_POSITIVE);
            mButton.setFilterTouchesWhenObscured(true);
        } catch (Exception e) {
            Log.e(TAG, "onResume", e);
            finish();
        }
    }

    @Override
    public Drawable getDrawable(String source) {
        // Should only reach this when fetching the VPN icon for the warning string.
        Drawable icon = getDrawable(R.drawable.ic_vpn_dialog);
        icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
        return icon;
    }

    @Override
    public void onBackPressed() {
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        try {
            if (mService.prepareVpn(null, mPackage)) {
                // Authorize this app to initiate VPN connections in the future without user
                // intervention.
                mService.setVpnPackageAuthorization(true);
                setResult(RESULT_OK);
            }
        } catch (Exception e) {
            Log.e(TAG, "onClick", e);
        }
    }
}
