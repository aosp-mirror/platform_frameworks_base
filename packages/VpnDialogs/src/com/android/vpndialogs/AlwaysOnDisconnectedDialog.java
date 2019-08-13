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

package com.android.vpndialogs;

import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.IConnectivityManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.net.VpnConfig;

public class AlwaysOnDisconnectedDialog extends AlertActivity
        implements DialogInterface.OnClickListener{

    private static final String TAG = "VpnDisconnected";

    private IConnectivityManager mService;
    private int mUserId;
    private String mVpnPackage;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mService = IConnectivityManager.Stub.asInterface(
                ServiceManager.getService(Context.CONNECTIVITY_SERVICE));
        mUserId = UserHandle.myUserId();
        mVpnPackage = getAlwaysOnVpnPackage();
        if (mVpnPackage == null) {
            finish();
            return;
        }

        View view = View.inflate(this, R.layout.always_on_disconnected, null);
        TextView messageView = view.findViewById(R.id.message);
        messageView.setText(getMessage(getIntent().getBooleanExtra("lockdown", false)));
        messageView.setMovementMethod(LinkMovementMethod.getInstance());

        mAlertParams.mTitle = getString(R.string.always_on_disconnected_title);
        mAlertParams.mPositiveButtonText = getString(R.string.open_app);
        mAlertParams.mPositiveButtonListener = this;
        mAlertParams.mNegativeButtonText = getString(R.string.dismiss);
        mAlertParams.mNegativeButtonListener = this;
        mAlertParams.mCancelable = false;
        mAlertParams.mView = view;
        setupAlert();

        getWindow().setCloseOnTouchOutside(false);
        getWindow().setType(TYPE_SYSTEM_ALERT);
        getWindow().addFlags(FLAG_ALT_FOCUSABLE_IM);
        getWindow().addPrivateFlags(PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case BUTTON_POSITIVE:
                PackageManager pm = getPackageManager();
                final Intent intent = pm.getLaunchIntentForPackage(mVpnPackage);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                }
                finish();
                break;
            case BUTTON_NEGATIVE:
                finish();
                break;
            default:
                break;
        }
    }

    private String getAlwaysOnVpnPackage() {
        try {
            return mService.getAlwaysOnVpnPackage(mUserId);
        } catch (RemoteException e) {
            Log.e(TAG, "Can't getAlwaysOnVpnPackage()", e);
            return null;
        }
    }

    private CharSequence getVpnLabel() {
        try {
            return VpnConfig.getVpnLabel(this, mVpnPackage);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Can't getVpnLabel() for " + mVpnPackage, e);
            return mVpnPackage;
        }
    }

    private CharSequence getMessage(boolean isLockdown) {
        final SpannableStringBuilder message = new SpannableStringBuilder();
        final int baseMessageResId = isLockdown
                ? R.string.always_on_disconnected_message_lockdown
                : R.string.always_on_disconnected_message;
        message.append(getString(baseMessageResId, getVpnLabel()));
        message.append(getString(R.string.always_on_disconnected_message_separator));
        message.append(getString(R.string.always_on_disconnected_message_settings_link),
                new VpnSpan(), 0 /*flags*/);
        return message;
    }

    private class VpnSpan extends ClickableSpan {
        @Override
        public void onClick(View unused) {
            final Intent intent = new Intent(Settings.ACTION_VPN_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
    }
}
