/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 *
 *                            DO NOT USE THIS CLASS:
 *
 *      Use android.telephony.TelephonyManager and PhoneStateListener instead.
 *
 *
 */
@Deprecated
public final class PhoneStateIntentReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "PHONE";
    private static final boolean DBG = false;

    private static final int NOTIF_PHONE    = 1 << 0;
    private static final int NOTIF_SERVICE  = 1 << 1;
    private static final int NOTIF_SIGNAL   = 1 << 2;

    private static final int NOTIF_MAX      = 1 << 5;

    Phone.State mPhoneState = Phone.State.IDLE;
    ServiceState mServiceState = new ServiceState();
    SignalStrength mSignalStrength = new SignalStrength();

    private Context mContext;
    private Handler mTarget;
    private IntentFilter mFilter;
    private int mWants;
    private int mPhoneStateEventWhat;
    private int mServiceStateEventWhat;
    private int mLocationEventWhat;
    private int mAsuEventWhat;

    public PhoneStateIntentReceiver() {
        super();
        mFilter = new IntentFilter();
    }

    public PhoneStateIntentReceiver(Context context, Handler target) {
        this();
        setContext(context);
        setTarget(target);
    }

    public void setContext(Context c) {
        mContext = c;
    }

    public void setTarget(Handler h) {
        mTarget = h;
    }

    public Phone.State getPhoneState() {
        if ((mWants & NOTIF_PHONE) == 0) {
            throw new RuntimeException
                ("client must call notifyPhoneCallState(int)");
        }
        return mPhoneState;
    }

    public ServiceState getServiceState() {
        if ((mWants & NOTIF_SERVICE) == 0) {
            throw new RuntimeException
                ("client must call notifyServiceState(int)");
        }
        return mServiceState;
    }

    /**
     * Returns current signal strength in as an asu 0..31
     *
     * Throws RuntimeException if client has not called notifySignalStrength()
     */
    public int getSignalStrengthLevelAsu() {
        // TODO: use new SignalStrength instead of asu
        if ((mWants & NOTIF_SIGNAL) == 0) {
            throw new RuntimeException
                ("client must call notifySignalStrength(int)");
        }
        return mSignalStrength.getAsuLevel();
    }

    /**
     * Return current signal strength in "dBm", ranging from -113 - -51dBm
     * or -1 if unknown
     *
     * @return signal strength in dBm, -1 if not yet updated
     * Throws RuntimeException if client has not called notifySignalStrength()
     */
    public int getSignalStrengthDbm() {
        if ((mWants & NOTIF_SIGNAL) == 0) {
            throw new RuntimeException
                ("client must call notifySignalStrength(int)");
        }
        return mSignalStrength.getDbm();
    }

    public void notifyPhoneCallState(int eventWhat) {
        mWants |= NOTIF_PHONE;
        mPhoneStateEventWhat = eventWhat;
        mFilter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
    }

    public boolean getNotifyPhoneCallState() {
        return ((mWants & NOTIF_PHONE) != 0);
    }

    public void notifyServiceState(int eventWhat) {
        mWants |= NOTIF_SERVICE;
        mServiceStateEventWhat = eventWhat;
        mFilter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
    }

    public boolean getNotifyServiceState() {
        return ((mWants & NOTIF_SERVICE) != 0);
    }

    public void notifySignalStrength (int eventWhat) {
        mWants |= NOTIF_SIGNAL;
        mAsuEventWhat = eventWhat;
        mFilter.addAction(TelephonyIntents.ACTION_SIGNAL_STRENGTH_CHANGED);
    }

    public boolean getNotifySignalStrength() {
        return ((mWants & NOTIF_SIGNAL) != 0);
    }

    public void registerIntent() {
        mContext.registerReceiver(this, mFilter);
    }

    public void unregisterIntent() {
        mContext.unregisterReceiver(this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        try {
            if (TelephonyIntents.ACTION_SIGNAL_STRENGTH_CHANGED.equals(action)) {
                mSignalStrength = SignalStrength.newFromBundle(intent.getExtras());

                if (mTarget != null && getNotifySignalStrength()) {
                    Message message = Message.obtain(mTarget, mAsuEventWhat);
                    mTarget.sendMessage(message);
                }
            } else if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
                if (DBG) Log.d(LOG_TAG, "onReceiveIntent: ACTION_PHONE_STATE_CHANGED, state="
                               + intent.getStringExtra(Phone.STATE_KEY));
                String phoneState = intent.getStringExtra(Phone.STATE_KEY);
                mPhoneState = (Phone.State) Enum.valueOf(
                        Phone.State.class, phoneState);

                if (mTarget != null && getNotifyPhoneCallState()) {
                    Message message = Message.obtain(mTarget,
                            mPhoneStateEventWhat);
                    mTarget.sendMessage(message);
                }
            } else if (TelephonyIntents.ACTION_SERVICE_STATE_CHANGED.equals(action)) {
                mServiceState = ServiceState.newFromBundle(intent.getExtras());

                if (mTarget != null && getNotifyServiceState()) {
                    Message message = Message.obtain(mTarget,
                            mServiceStateEventWhat);
                    mTarget.sendMessage(message);
                }
            }
        } catch (Exception ex) {
            Log.e(LOG_TAG, "[PhoneStateIntentRecv] caught " + ex);
            ex.printStackTrace();
        }
    }

}
