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

import android.util.Log;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maintain the Apn context
 */
public class ApnContext {

    public final String LOG_TAG;

    protected static final boolean DBG = true;

    private final String mApnType;

    private DataConnectionTracker.State mState;

    private ArrayList<ApnSetting> mWaitingApns = null;

    /** A zero indicates that all waiting APNs had a permanent error */
    private AtomicInteger mWaitingApnsPermanentFailureCountDown;

    private ApnSetting mApnSetting;

    DataConnection mDataConnection;

    DataConnectionAc mDataConnectionAc;

    String mReason;

    /**
     * user/app requested connection on this APN
     */
    AtomicBoolean mDataEnabled;

    /**
     * carrier requirements met
     */
    AtomicBoolean mDependencyMet;

    public ApnContext(String apnType, String logTag) {
        mApnType = apnType;
        mState = DataConnectionTracker.State.IDLE;
        setReason(Phone.REASON_DATA_ENABLED);
        mDataEnabled = new AtomicBoolean(false);
        mDependencyMet = new AtomicBoolean(true);
        mWaitingApnsPermanentFailureCountDown = new AtomicInteger(0);
        LOG_TAG = logTag;
    }

    public String getApnType() {
        return mApnType;
    }

    public synchronized DataConnection getDataConnection() {
        return mDataConnection;
    }

    public synchronized void setDataConnection(DataConnection dataConnection) {
        mDataConnection = dataConnection;
    }


    public synchronized DataConnectionAc getDataConnectionAc() {
        return mDataConnectionAc;
    }

    public synchronized void setDataConnectionAc(DataConnectionAc dcac) {
        if (dcac != null) {
            dcac.addApnContextSync(this);
        } else {
            if (mDataConnectionAc != null) mDataConnectionAc.removeApnContextSync(this);
        }
        mDataConnectionAc = dcac;
    }

    public synchronized ApnSetting getApnSetting() {
        return mApnSetting;
    }

    public synchronized void setApnSetting(ApnSetting apnSetting) {
        mApnSetting = apnSetting;
    }

    public synchronized void setWaitingApns(ArrayList<ApnSetting> waitingApns) {
        mWaitingApns = waitingApns;
        mWaitingApnsPermanentFailureCountDown.set(mWaitingApns.size());
    }

    public int getWaitingApnsPermFailCount() {
        return mWaitingApnsPermanentFailureCountDown.get();
    }

    public void decWaitingApnsPermFailCount() {
        mWaitingApnsPermanentFailureCountDown.decrementAndGet();
    }

    public synchronized ApnSetting getNextWaitingApn() {
        ArrayList<ApnSetting> list = mWaitingApns;
        ApnSetting apn = null;

        if (list != null) {
            if (!list.isEmpty()) {
                apn = list.get(0);
            }
        }
        return apn;
    }

    public synchronized void removeNextWaitingApn() {
        if ((mWaitingApns != null) && (!mWaitingApns.isEmpty())) {
            mWaitingApns.remove(0);
        }
    }

    public synchronized ArrayList<ApnSetting> getWaitingApns() {
        return mWaitingApns;
    }

    public synchronized void setState(DataConnectionTracker.State s) {
        if (DBG) {
            log("setState: " + s + " for type " + mApnType + ", previous state:" + mState);
        }

        mState = s;

        if (mState == DataConnectionTracker.State.FAILED) {
            if (mWaitingApns != null) {
                mWaitingApns.clear(); // when teardown the connection and set to IDLE
            }
        }
    }

    public synchronized DataConnectionTracker.State getState() {
        return mState;
    }

    public boolean isDisconnected() {
        DataConnectionTracker.State currentState = getState();
        return ((currentState == DataConnectionTracker.State.IDLE) ||
                    currentState == DataConnectionTracker.State.FAILED);
    }

    public synchronized void setReason(String reason) {
        if (DBG) {
            log("set reason as " + reason + ", for type " + mApnType + ",current state " + mState);
        }
        mReason = reason;
    }

    public synchronized String getReason() {
        return mReason;
    }

    public boolean isReady() {
        return mDataEnabled.get() && mDependencyMet.get();
    }

    public void setEnabled(boolean enabled) {
        if (DBG) {
            log("set enabled as " + enabled + ", for type " +
                    mApnType + ", current state is " + mDataEnabled.get());
        }
        mDataEnabled.set(enabled);
    }

    public boolean isEnabled() {
        return mDataEnabled.get();
    }

    public void setDependencyMet(boolean met) {
        if (DBG) {
            log("set mDependencyMet as " + met + ", for type " + mApnType +
                    ", current state is " + mDependencyMet.get());
        }
        mDependencyMet.set(met);
    }

    public boolean getDependencyMet() {
       return mDependencyMet.get();
    }

    @Override
    public String toString() {
        return "state=" + getState() + " apnType=" + mApnType;
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "[ApnContext] " + s);
    }
}
