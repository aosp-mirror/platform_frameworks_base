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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.IConnectivityManager;
import android.os.ServiceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

public class ConfirmDialog extends Activity implements CompoundButton.OnCheckedChangeListener,
        DialogInterface.OnClickListener, DialogInterface.OnDismissListener {
    private static final String TAG = "VpnConfirm";

    private String mPackage;

    private IConnectivityManager mService;

    private AlertDialog mDialog;
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

            PackageManager pm = getPackageManager();
            ApplicationInfo app = pm.getApplicationInfo(mPackage, 0);

            View view = View.inflate(this, R.layout.confirm, null);
            ((ImageView) view.findViewById(R.id.icon)).setImageDrawable(app.loadIcon(pm));
            ((TextView) view.findViewById(R.id.prompt)).setText(
                    getString(R.string.prompt, app.loadLabel(pm)));
            ((CompoundButton) view.findViewById(R.id.check)).setOnCheckedChangeListener(this);

            mDialog = new AlertDialog.Builder(this)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle(android.R.string.dialog_alert_title)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok, this)
                    .setNegativeButton(android.R.string.cancel, this)
                    .setCancelable(false)
                    .create();
            mDialog.setOnDismissListener(this);
            mDialog.show();

            mButton = mDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            mButton.setEnabled(false);
        } catch (Exception e) {
            Log.e(TAG, "onResume", e);
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mDialog != null) {
            mDialog.setOnDismissListener(null);
            mDialog.dismiss();
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton button, boolean checked) {
        mButton.setEnabled(checked);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        try {
            if (which == AlertDialog.BUTTON_POSITIVE && mService.prepareVpn(null, mPackage)) {
                setResult(RESULT_OK);
            }
        } catch (Exception e) {
            Log.e(TAG, "onClick", e);
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        finish();
    }
}
