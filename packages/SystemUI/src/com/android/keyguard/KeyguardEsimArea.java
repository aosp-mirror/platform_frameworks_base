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

package com.android.keyguard;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.euicc.EuiccManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import com.android.systemui.res.R;

/***
 * This button is used by the device with embedded SIM card to disable current carrier to unlock
 * the device with no cellular service.
 */
class KeyguardEsimArea extends Button implements View.OnClickListener {
    private static final String ACTION_DISABLE_ESIM = "com.android.keyguard.disable_esim";
    private static final String TAG = "KeyguardEsimArea";
    private static final String PERMISSION_SELF = "com.android.systemui.permission.SELF";

    private int mSubscriptionId;
    private EuiccManager mEuiccManager;

    private BroadcastReceiver mReceiver =
        new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_DISABLE_ESIM.equals(intent.getAction())) {
                    int resultCode = getResultCode();
                    if (resultCode != EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
                        Log.e(TAG, "Error disabling esim, result code = " + resultCode);
                        AlertDialog.Builder builder =
                                new AlertDialog.Builder(mContext)
                                        .setMessage(R.string.error_disable_esim_msg)
                                        .setTitle(R.string.error_disable_esim_title)
                                        .setCancelable(false /* cancelable */)
                                        .setPositiveButton(R.string.ok, null /* listener */);
                        AlertDialog alertDialog = builder.create();
                        alertDialog.getWindow().setType(
                                WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
                        alertDialog.show();
                    }
                }
            }
        };

    public KeyguardEsimArea(Context context) {
        this(context, null);
    }

    public KeyguardEsimArea(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardEsimArea(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, android.R.style.Widget_Material_Button_Borderless);
    }

    public KeyguardEsimArea(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mEuiccManager = (EuiccManager) context.getSystemService(Context.EUICC_SERVICE);
        setOnClickListener(this);
    }

    public void setSubscriptionId(int subscriptionId) {
        mSubscriptionId = subscriptionId;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mContext.registerReceiver(mReceiver, new IntentFilter(ACTION_DISABLE_ESIM),
                PERMISSION_SELF, null /* scheduler */,
                Context.RECEIVER_EXPORTED_UNAUDITED);
    }

    public static boolean isEsimLocked(Context context, int subId) {
        EuiccManager euiccManager =
                (EuiccManager) context.getSystemService(Context.EUICC_SERVICE);
        if (!euiccManager.isEnabled()) {
            return false;
        }
        SubscriptionInfo sub = SubscriptionManager.from(context).getActiveSubscriptionInfo(subId);
        return  sub != null && sub.isEmbedded();
    }

    @Override
    protected void onDetachedFromWindow() {
        mContext.unregisterReceiver(mReceiver);
        super.onDetachedFromWindow();
    }

    @Override
    public void onClick(View v) {
        SubscriptionInfo sub = SubscriptionManager.from(mContext)
                .getActiveSubscriptionInfo(mSubscriptionId);
        if (sub == null) {
            Log.e(TAG, "No active subscription with subscriptionId: " + mSubscriptionId);
            return;
        }
        Intent intent = new Intent(ACTION_DISABLE_ESIM);
        intent.setPackage(mContext.getPackageName());
        PendingIntent callbackIntent = PendingIntent.getBroadcastAsUser(
            mContext,
            0 /* requestCode */,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE_UNAUDITED, UserHandle.SYSTEM);
        mEuiccManager.switchToSubscription(
                SubscriptionManager.INVALID_SUBSCRIPTION_ID, sub.getPortIndex(), callbackIntent);
    }
}
