package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.widget.LockPatternUtils;

public class CarrierText extends TextView {
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
    };
    /**
     * The status of this lock screen. Primarily used for widgets on LockScreen.
     */
    enum StatusMode {
        Normal, // Normal case (sim card present, it's not locked)
        NetworkLocked, // SIM card is 'network locked'.
        SimMissing, // SIM card is missing.
        SimMissingLocked, // SIM card is missing, and device isn't provisioned; don't allow access
        SimPukLocked, // SIM card is PUK locked because SIM entered wrong too many times
        SimLocked, // SIM card is currently locked
        SimPermDisabled; // SIM card is permanently disabled due to PUK unlock failure
    }

    public CarrierText(Context context) {
        this(context, null);
    }

    public CarrierText(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLockPatternUtils = new LockPatternUtils(mContext);
    }

    protected void updateCarrierText(State simState, CharSequence plmn, CharSequence spn) {
        setText(getCarrierTextForSimState(simState, plmn, spn));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mCallback);
        setSelected(true); // Allow marquee to work.
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

            case NetworkLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        mContext.getText(R.string.lockscreen_network_locked_message), plmn);
                break;

            case SimMissing:
                // Shows "No SIM card | Emergency calls only" on devices that are voice-capable.
                // This depends on mPlmn containing the text "Emergency calls only" when the radio
                // has some connectivity. Otherwise, it should be null or empty and just show
                // "No SIM card"
                carrierText =  makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.lockscreen_missing_sim_message_short),
                        plmn);
                break;

            case SimPermDisabled:
                carrierText = getContext().getText(
                        R.string.lockscreen_permanent_disabled_sim_message_short);
                break;

            case SimMissingLocked:
                carrierText =  makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.lockscreen_missing_sim_message_short),
                        plmn);
                break;

            case SimLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.lockscreen_sim_locked_message),
                        plmn);
                break;

            case SimPukLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.lockscreen_sim_puk_locked_message),
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
                return StatusMode.SimMissing;
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
            return plmn + "|" + spn;
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
                carrierHelpTextId = R.string.lockscreen_instructions_when_pattern_disabled;
                break;

            case SimMissing:
                carrierHelpTextId = R.string.lockscreen_missing_sim_instructions_long;
                break;

            case SimPermDisabled:
                carrierHelpTextId = R.string.lockscreen_permanent_disabled_sim_instructions;
                break;

            case SimMissingLocked:
                carrierHelpTextId = R.string.lockscreen_missing_sim_instructions;
                break;

            case Normal:
            case SimLocked:
            case SimPukLocked:
                break;
        }

        return mContext.getText(carrierHelpTextId);
    }
}
