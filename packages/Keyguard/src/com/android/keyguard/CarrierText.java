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

import android.content.Context;
import android.text.method.SingleLineTransformationMethod;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.widget.LockPatternUtils;

import java.util.Locale;

public class CarrierText extends TextView {
    private static CharSequence mSeparator;

    private LockPatternUtils mLockPatternUtils;

    private KeyguardUpdateMonitorCallback mCallback = new KeyguardUpdateMonitorCallback() {
        private CharSequence mPlmn;
        private CharSequence mSpn;
        private State mSimState;

        @Override
        public void onRefreshCarrierInfo(CharSequence plmn, CharSequence spn) {
            mPlmn = plmn;
            mSpn = spn;
            updateCarrierText(mSimState, mPlmn, mSpn);
        }

        @Override
        public void onSimStateChanged(IccCardConstants.State simState) {
            mSimState = simState;
            updateCarrierText(mSimState, mPlmn, mSpn);
        }

        public void onScreenTurnedOff(int why) {
            setSelected(false);
        };

        public void onScreenTurnedOn() {
            setSelected(true);
        };
    };
    /**
     * The status of this lock screen. Primarily used for widgets on LockScreen.
     */
    private static enum StatusMode {
        Normal, // Normal case (sim card present, it's not locked)
        NetworkLocked, // SIM card is 'network locked'.
        SimMissing, // SIM card is missing.
        SimMissingLocked, // SIM card is missing, and device isn't provisioned; don't allow access
        SimPukLocked, // SIM card is PUK locked because SIM entered wrong too many times
        SimLocked, // SIM card is currently locked
        SimPermDisabled, // SIM card is permanently disabled due to PUK unlock failure
        SimNotReady; // SIM is not ready yet. May never be on devices w/o a SIM.
    }

    public CarrierText(Context context) {
        this(context, null);
    }

    public CarrierText(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLockPatternUtils = new LockPatternUtils(mContext);
        boolean useAllCaps = mContext.getResources().getBoolean(R.bool.kg_use_all_caps);
        setTransformationMethod(new CarrierTextTransformationMethod(mContext, useAllCaps));
    }

    protected void updateCarrierText(State simState, CharSequence plmn, CharSequence spn) {
        setText(getCarrierTextForSimState(simState, plmn, spn));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSeparator = getResources().getString(R.string.kg_text_message_separator);
        final boolean screenOn = KeyguardUpdateMonitor.getInstance(mContext).isScreenOn();
        setSelected(screenOn); // Allow marquee to work.
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mCallback);
    }

    /**
     * Top-level function for creating carrier text. Makes text based on simState, PLMN
     * and SPN as well as device capabilities, such as being emergency call capable.
     *
     * @param simState
     * @param plmn
     * @param spn
     * @return
     */
    private CharSequence getCarrierTextForSimState(IccCardConstants.State simState,
            CharSequence plmn, CharSequence spn) {
        CharSequence carrierText = null;
        StatusMode status = getStatusForIccState(simState);
        switch (status) {
            case Normal:
                carrierText = concatenate(plmn, spn);
                break;

            case SimNotReady:
                carrierText = null; // nothing to display yet.
                break;

            case NetworkLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        mContext.getText(R.string.keyguard_network_locked_message), plmn);
                break;

            case SimMissing:
                // Shows "No SIM card | Emergency calls only" on devices that are voice-capable.
                // This depends on mPlmn containing the text "Emergency calls only" when the radio
                // has some connectivity. Otherwise, it should be null or empty and just show
                // "No SIM card"
                carrierText =  makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_missing_sim_message_short),
                        plmn);
                break;

            case SimPermDisabled:
                carrierText = getContext().getText(
                        R.string.keyguard_permanent_disabled_sim_message_short);
                break;

            case SimMissingLocked:
                carrierText =  makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_missing_sim_message_short),
                        plmn);
                break;

            case SimLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_sim_locked_message),
                        plmn);
                break;

            case SimPukLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_sim_puk_locked_message),
                        plmn);
                break;
        }

        return carrierText;
    }

    /*
     * Add emergencyCallMessage to carrier string only if phone supports emergency calls.
     */
    private CharSequence makeCarrierStringOnEmergencyCapable(
            CharSequence simMessage, CharSequence emergencyCallMessage) {
        if (mLockPatternUtils.isEmergencyCallCapable()) {
            return concatenate(simMessage, emergencyCallMessage);
        }
        return simMessage;
    }

    /**
     * Determine the current status of the lock screen given the SIM state and other stuff.
     */
    private StatusMode getStatusForIccState(IccCardConstants.State simState) {
        // Since reading the SIM may take a while, we assume it is present until told otherwise.
        if (simState == null) {
            return StatusMode.Normal;
        }

        final boolean missingAndNotProvisioned =
                !KeyguardUpdateMonitor.getInstance(mContext).isDeviceProvisioned()
                && (simState == IccCardConstants.State.ABSENT ||
                        simState == IccCardConstants.State.PERM_DISABLED);

        // Assume we're NETWORK_LOCKED if not provisioned
        simState = missingAndNotProvisioned ? IccCardConstants.State.NETWORK_LOCKED : simState;
        switch (simState) {
            case ABSENT:
                return StatusMode.SimMissing;
            case NETWORK_LOCKED:
                return StatusMode.SimMissingLocked;
            case NOT_READY:
                return StatusMode.SimNotReady;
            case PIN_REQUIRED:
                return StatusMode.SimLocked;
            case PUK_REQUIRED:
                return StatusMode.SimPukLocked;
            case READY:
                return StatusMode.Normal;
            case PERM_DISABLED:
                return StatusMode.SimPermDisabled;
            case UNKNOWN:
                return StatusMode.SimMissing;
        }
        return StatusMode.SimMissing;
    }

    private static CharSequence concatenate(CharSequence plmn, CharSequence spn) {
        final boolean plmnValid = !TextUtils.isEmpty(plmn);
        final boolean spnValid = !TextUtils.isEmpty(spn);
        if (plmnValid && spnValid) {
            return new StringBuilder().append(plmn).append(mSeparator).append(spn).toString();
        } else if (plmnValid) {
            return plmn;
        } else if (spnValid) {
            return spn;
        } else {
            return "";
        }
    }

    private CharSequence getCarrierHelpTextForSimState(IccCardConstants.State simState,
            String plmn, String spn) {
        int carrierHelpTextId = 0;
        StatusMode status = getStatusForIccState(simState);
        switch (status) {
            case NetworkLocked:
                carrierHelpTextId = R.string.keyguard_instructions_when_pattern_disabled;
                break;

            case SimMissing:
                carrierHelpTextId = R.string.keyguard_missing_sim_instructions_long;
                break;

            case SimPermDisabled:
                carrierHelpTextId = R.string.keyguard_permanent_disabled_sim_instructions;
                break;

            case SimMissingLocked:
                carrierHelpTextId = R.string.keyguard_missing_sim_instructions;
                break;

            case Normal:
            case SimLocked:
            case SimPukLocked:
                break;
        }

        return mContext.getText(carrierHelpTextId);
    }

    private class CarrierTextTransformationMethod extends SingleLineTransformationMethod {
        private final Locale mLocale;
        private final boolean mAllCaps;

        public CarrierTextTransformationMethod(Context context, boolean allCaps) {
            mLocale = context.getResources().getConfiguration().locale;
            mAllCaps = allCaps;
        }

        @Override
        public CharSequence getTransformation(CharSequence source, View view) {
            source = super.getTransformation(source, view);

            if (mAllCaps && source != null) {
                source = source.toString().toUpperCase(mLocale);
            }

            return source;
        }
    }
}
