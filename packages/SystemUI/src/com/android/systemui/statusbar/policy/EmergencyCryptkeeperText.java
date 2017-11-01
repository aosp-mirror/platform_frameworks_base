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

package com.android.systemui.statusbar.policy;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;

import java.util.List;

public class EmergencyCryptkeeperText extends TextView {

    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardUpdateMonitorCallback mCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onPhoneStateChanged(int phoneState) {
            update();
        }

        @Override
        public void onRefreshCarrierInfo() {
            update();
        }
    };
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(intent.getAction())) {
                update();
            }
        }
    };

    public EmergencyCryptkeeperText(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setVisibility(GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        mKeyguardUpdateMonitor.registerCallback(mCallback);
        getContext().registerReceiver(mReceiver,
                new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED));
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mKeyguardUpdateMonitor != null) {
            mKeyguardUpdateMonitor.removeCallback(mCallback);
        }
        getContext().unregisterReceiver(mReceiver);
    }

    public void update() {
        boolean hasMobile = ConnectivityManager.from(mContext)
                .isNetworkSupported(ConnectivityManager.TYPE_MOBILE);
        boolean airplaneMode = (Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1);

        if (!hasMobile || airplaneMode) {
            setText(null);
            setVisibility(GONE);
            return;
        }

        boolean allSimsMissing = true;
        CharSequence displayText = null;

        List<SubscriptionInfo> subs = mKeyguardUpdateMonitor.getSubscriptionInfo(false);
        final int N = subs.size();
        for (int i = 0; i < N; i++) {
            int subId = subs.get(i).getSubscriptionId();
            IccCardConstants.State simState = mKeyguardUpdateMonitor.getSimState(subId);
            CharSequence carrierName = subs.get(i).getCarrierName();
            if (simState.iccCardExist() && !TextUtils.isEmpty(carrierName)) {
                allSimsMissing = false;
                displayText = carrierName;
            }
        }
        if (allSimsMissing) {
            if (N != 0) {
                // Shows "Emergency calls only" on devices that are voice-capable.
                // This depends on mPlmn containing the text "Emergency calls only" when the radio
                // has some connectivity. Otherwise it should show "No service"
                // Grab the first subscription, because they all should contain the emergency text,
                // described above.
                displayText = subs.get(0).getCarrierName();
            } else {
                // We don't have a SubscriptionInfo to get the emergency calls only from.
                // Grab it from the old sticky broadcast if possible instead. We can use it
                // here because no subscriptions are active, so we don't have
                // to worry about MSIM clashing.
                displayText = getContext().getText(
                        com.android.internal.R.string.emergency_calls_only);
                Intent i = getContext().registerReceiver(null,
                        new IntentFilter(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION));
                if (i != null) {
                    displayText = i.getStringExtra(TelephonyIntents.EXTRA_PLMN);
                }
            }
        }

        setText(displayText);
        setVisibility(TextUtils.isEmpty(displayText) ? GONE : VISIBLE);
    }
}
