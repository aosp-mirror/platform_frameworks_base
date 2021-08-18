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
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

import com.android.settingslib.WirelessUtils;

import java.util.List;

/** Shows the operator name */
public class OperatorNameView extends TextView {
    private boolean mDemoMode;

    public OperatorNameView(Context context) {
        this(context, null);
    }

    public OperatorNameView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OperatorNameView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    void setDemoMode(boolean demoMode) {
        mDemoMode = demoMode;
    }

    void update(boolean showOperatorName, boolean hasMobile,
            List<OperatorNameViewController.SubInfo> subs) {
        setVisibility(showOperatorName ? VISIBLE : GONE);

        boolean airplaneMode = WirelessUtils.isAirplaneModeOn(mContext);
        if (!hasMobile || airplaneMode) {
            setText(null);
            setVisibility(GONE);
            return;
        }

        if (!mDemoMode) {
            updateText(subs);
        }
    }

    void updateText(List<OperatorNameViewController.SubInfo> subs) {
        CharSequence displayText = null;
        final int N = subs.size();
        for (int i = 0; i < N; i++) {
            OperatorNameViewController.SubInfo subInfo = subs.get(i);
            CharSequence carrierName = subs.get(i).getCarrierName();
            if (!TextUtils.isEmpty(carrierName) && subInfo.simReady()) {
                if (subInfo.stateInService()) {
                    displayText = subInfo.getCarrierName();
                    break;
                }
            }
        }

        setText(displayText);
    }
}
