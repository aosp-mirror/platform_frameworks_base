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

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.telephony.SubscriptionManager;
import android.telephony.euicc.EuiccManager;

import java.lang.ref.WeakReference;

/***
 * This button is used by the device with embedded SIM card to disable current carrier to unlock
 * the device with no cellular service.
 */
class KeyguardEsimArea extends Button implements View.OnClickListener {
    private EuiccManager mEuiccManager;

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
        setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        // STOPSHIP(b/37353596): use EuiccManager API to disable current carrier.
    }

    public static boolean isEsimLocked(Context context, int subId) {
        EuiccManager euiccManager =
                (EuiccManager) context.getSystemService(Context.EUICC_SERVICE);
        return euiccManager.isEnabled()
                && SubscriptionManager.from(context).getActiveSubscriptionInfo(subId).isEmbedded();
    }

}
