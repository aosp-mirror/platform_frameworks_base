/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static com.android.systemui.util.PluralMessageFormaterKt.icuMessageFormat;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;

import com.android.systemui.res.R;

/**
 * Displays a PIN pad for entering a PUK (Pin Unlock Kode) provided by a carrier.
 */
public class KeyguardSimPukView extends KeyguardSimInputView {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    public static final String TAG = "KeyguardSimPukView";

    public KeyguardSimPukView(Context context) {
        this(context, null);
    }

    public KeyguardSimPukView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected int getPromptReasonStringRes(int reason) {
        // No message on SIM Puk
        return 0;
    }

    String getPukPasswordErrorMessage(
            int attemptsRemaining, boolean isDefault, boolean isEsimLocked) {
        String displayMessage;

        if (attemptsRemaining == 0) {
            displayMessage = getContext().getString(R.string.kg_password_wrong_puk_code_dead);
        } else if (attemptsRemaining > 0) {
            int msgId = isDefault ? R.string.kg_password_default_puk_message :
                    R.string.kg_password_wrong_puk_code;
            displayMessage = icuMessageFormat(getResources(), msgId, attemptsRemaining);
        } else {
            int msgId = isDefault ? R.string.kg_puk_enter_puk_hint :
                    R.string.kg_password_puk_failed;
            displayMessage = getContext().getString(msgId);
        }
        if (isEsimLocked) {
            displayMessage = getResources()
                    .getString(R.string.kg_sim_lock_esim_instructions, displayMessage);
        }
        if (DEBUG) {
            Log.d(TAG, "getPukPasswordErrorMessage:"
                    + " attemptsRemaining=" + attemptsRemaining
                    + " displayMessage=" + displayMessage);
        }
        return displayMessage;
    }

    @Override
    protected int getPasswordTextViewId() {
        return R.id.pukEntry;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        if (mEcaView instanceof EmergencyCarrierArea) {
            ((EmergencyCarrierArea) mEcaView).setCarrierTextVisible(true);
        }
    }

    @Override
    public void startAppearAnimation() {
        // noop.
    }

    @Override
    public CharSequence getTitle() {
        return getContext().getString(
                com.android.internal.R.string.keyguard_accessibility_sim_puk_unlock);
    }
}
