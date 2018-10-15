/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.slice.SliceManager;
import android.app.slice.SliceProvider;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.text.BidiFormatter;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.TextView;

public class SlicePermissionActivity extends Activity implements OnClickListener,
        OnDismissListener {

    private static final String TAG = "SlicePermissionActivity";

    private CheckBox mAllCheckbox;

    private Uri mUri;
    private String mCallingPkg;
    private String mProviderPkg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mUri = getIntent().getParcelableExtra(SliceProvider.EXTRA_BIND_URI);
        mCallingPkg = getIntent().getStringExtra(SliceProvider.EXTRA_PKG);
        mProviderPkg = getIntent().getStringExtra(SliceProvider.EXTRA_PROVIDER_PKG);

        try {
            PackageManager pm = getPackageManager();
            CharSequence app1 = BidiFormatter.getInstance().unicodeWrap(pm.getApplicationInfo(
                    mCallingPkg, 0).loadSafeLabel(pm, PackageItemInfo.DEFAULT_MAX_LABEL_SIZE_PX,
                    PackageItemInfo.SAFE_LABEL_FLAG_TRIM
                            | PackageItemInfo.SAFE_LABEL_FLAG_FIRST_LINE).toString());
            CharSequence app2 = BidiFormatter.getInstance().unicodeWrap(pm.getApplicationInfo(
                    mProviderPkg, 0).loadSafeLabel(pm, PackageItemInfo.DEFAULT_MAX_LABEL_SIZE_PX,
                    PackageItemInfo.SAFE_LABEL_FLAG_TRIM
                            | PackageItemInfo.SAFE_LABEL_FLAG_FIRST_LINE).toString());
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.slice_permission_title, app1, app2))
                    .setView(R.layout.slice_permission_request)
                    .setNegativeButton(R.string.slice_permission_deny, this)
                    .setPositiveButton(R.string.slice_permission_allow, this)
                    .setOnDismissListener(this)
                    .create();
            dialog.getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
            dialog.show();
            TextView t1 = dialog.getWindow().getDecorView().findViewById(R.id.text1);
            t1.setText(getString(R.string.slice_permission_text_1, app2));
            TextView t2 = dialog.getWindow().getDecorView().findViewById(R.id.text2);
            t2.setText(getString(R.string.slice_permission_text_2, app2));
            mAllCheckbox = dialog.getWindow().getDecorView().findViewById(
                    R.id.slice_permission_checkbox);
            mAllCheckbox.setText(getString(R.string.slice_permission_checkbox, app1));
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Couldn't find package", e);
            finish();
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            getSystemService(SliceManager.class).grantPermissionFromUser(mUri, mCallingPkg,
                    mAllCheckbox.isChecked());
        }
        finish();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        finish();
    }
}
