/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.WirelessUtils;
import com.android.systemui.Dependency;
import com.android.systemui.demomode.DemoModeCommandReceiver;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.List;

/** Shows the operator name */
public class OperatorNameView extends TextView implements DemoModeCommandReceiver, DarkReceiver,
        SignalCallback, Tunable {

    private static final String KEY_SHOW_OPERATOR_NAME = "show_operator_name";

    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private boolean mDemoMode;

    private final KeyguardUpdateMonitorCallback mCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onRefreshCarrierInfo() {
            updateText();
        }
    };

    public OperatorNameView(Context context) {
        this(context, null);
    }

    public OperatorNameView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OperatorNameView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mKeyguardUpdateMonitor = Dependency.get(KeyguardUpdateMonitor.class);
        mKeyguardUpdateMonitor.registerCallback(mCallback);
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(this);
        Dependency.get(NetworkController.class).addCallback(this);
        Dependency.get(TunerService.class).addTunable(this, KEY_SHOW_OPERATOR_NAME);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mKeyguardUpdateMonitor.removeCallback(mCallback);
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(this);
        Dependency.get(NetworkController.class).removeCallback(this);
        Dependency.get(TunerService.class).removeTunable(this);
    }

    @Override
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        setTextColor(DarkIconDispatcher.getTint(area, this, tint));
    }

    @Override
    public void setIsAirplaneMode(IconState icon) {
        update();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        update();
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        setText(args.getString("name"));
    }

    @Override
    public void onDemoModeStarted() {
        mDemoMode = true;
    }

    @Override
    public void onDemoModeFinished() {
        mDemoMode = false;
        update();
    }

    private void update() {
        boolean showOperatorName = Dependency.get(TunerService.class)
                .getValue(KEY_SHOW_OPERATOR_NAME, 1) != 0;
        setVisibility(showOperatorName ? VISIBLE : GONE);

        boolean hasMobile = mContext.getSystemService(TelephonyManager.class).isDataCapable();
        boolean airplaneMode = WirelessUtils.isAirplaneModeOn(mContext);
        if (!hasMobile || airplaneMode) {
            setText(null);
            setVisibility(GONE);
            return;
        }

        if (!mDemoMode) {
            updateText();
        }
    }

    private void updateText() {
        CharSequence displayText = null;
        List<SubscriptionInfo> subs = mKeyguardUpdateMonitor.getFilteredSubscriptionInfo(false);
        final int N = subs.size();
        for (int i = 0; i < N; i++) {
            int subId = subs.get(i).getSubscriptionId();
            int simState = mKeyguardUpdateMonitor.getSimState(subId);
            CharSequence carrierName = subs.get(i).getCarrierName();
            if (!TextUtils.isEmpty(carrierName) && simState == TelephonyManager.SIM_STATE_READY) {
                ServiceState ss = mKeyguardUpdateMonitor.getServiceState(subId);
                if (ss != null && ss.getState() == ServiceState.STATE_IN_SERVICE) {
                    displayText = carrierName;
                    break;
                }
            }
        }

        setText(displayText);
    }
}
