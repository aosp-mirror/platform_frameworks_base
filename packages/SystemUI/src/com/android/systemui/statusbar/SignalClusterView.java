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

package com.android.systemui.statusbar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.statusbar.policy.NetworkController;

import com.android.systemui.R;

public class SignalClusterView
        extends LinearLayout 
        implements NetworkController.SignalCluster {

    NetworkController mNC;

    ViewGroup mWifiGroup, mMobileGroup;
    ImageView mWifi, mMobile, mWifiActivity, mMobileActivity, mMobileType;

    public SignalClusterView(Context context) {
        this(context, null);
    }

    public SignalClusterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SignalClusterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setNetworkController(NetworkController nc) {
        mNC = nc;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mWifiGroup      = (ViewGroup) findViewById(R.id.wifi_combo);
        mWifi           = (ImageView) findViewById(R.id.wifi_signal);
        mWifiActivity   = (ImageView) findViewById(R.id.wifi_inout);
        mMobileGroup    = (ViewGroup) findViewById(R.id.mobile_combo);
        mMobile         = (ImageView) findViewById(R.id.mobile_signal);
        mMobileActivity = (ImageView) findViewById(R.id.mobile_inout);
        mMobileType     = (ImageView) findViewById(R.id.mobile_type);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    public void setWifiIndicators(boolean visible, int strengthIcon, int activityIcon) {
        if (mWifiGroup == null) return;

        if (visible) {
            mWifiGroup.setVisibility(View.VISIBLE);
            mWifi.setImageResource(strengthIcon);
            mWifiActivity.setImageResource(activityIcon);
        } else {
            mWifiGroup.setVisibility(View.GONE);
        }
    }

    public void setMobileDataIndicators(boolean visible, int strengthIcon, int activityIcon,
            int typeIcon) {
        if (mMobileGroup == null) return;

        if (visible) {
            mMobileGroup.setVisibility(View.VISIBLE);
            mMobile.setImageResource(strengthIcon);
            mMobileActivity.setImageResource(activityIcon);
            mMobileType.setImageResource(typeIcon);
        } else {
            mMobileGroup.setVisibility(View.GONE);
        }
    }
}

