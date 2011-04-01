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

import android.app.PendingIntent;

import android.util.Log;
import java.util.ArrayList;
import com.android.internal.telephony.gsm.GsmDataConnection;

/**
 * Maintain the Apn context
 */
public class ApnContext {

    public static final int PENDING_ACTION_NONE = 1;
    public static final int PENDING_ACTION_RECONNECT = 2;
    public static final int PENDING_ACTION_APN_DISABLE = 3;
    public final String LOG_TAG;

    int pendingAction;

    protected static final boolean DBG = true;

    String mApnType;

    DataConnectionTracker.State mState;

    ArrayList<ApnSetting> mWaitingApns = null;

    /** A zero indicates that all waiting APNs had a permanent error */
    private int mWaitingApnsPermanentFailureCountDown;

    ApnSetting mApnSetting;

    GsmDataConnection mDataConnection;

    String mReason;

    PendingIntent mReconnectIntent;

    public ApnContext(String apnType, String logTag) {
        mApnType = apnType;
        mState = DataConnectionTracker.State.IDLE;
        setReason(Phone.REASON_DATA_ENABLED);
        pendingAction = PENDING_ACTION_NONE;
        LOG_TAG = logTag;
    }

    public int getPendingAction() {
        return pendingAction;
    }

    public void setPendingAction(int pa) {
        pendingAction = pa;
    }

    public String getApnType() {
        return mApnType;
    }

    public GsmDataConnection getDataConnection() {
        return mDataConnection;
    }

    public void setDataConnection(GsmDataConnection dataConnection) {
        mDataConnection = dataConnection;
    }

    public ApnSetting getApnSetting() {
        return mApnSetting;
    }

    public void setApnSetting(ApnSetting apnSetting) {
        mApnSetting = apnSetting;
    }

    public void setWaitingApns(ArrayList<ApnSetting> waitingApns) {
        mWaitingApns = waitingApns;
        mWaitingApnsPermanentFailureCountDown = mWaitingApns.size();
    }

    public int getWaitingApnsPermFailCount() {
        return mWaitingApnsPermanentFailureCountDown;
    }

    public void decWaitingApnsPermFailCount() {
        mWaitingApnsPermanentFailureCountDown--;
    }

    public ApnSetting getNextWaitingApn() {
        ArrayList<ApnSetting> list = mWaitingApns;
        ApnSetting apn = null;

        if (list != null) {
            if (!list.isEmpty()) {
                apn = list.get(0);
            }
        }
        return apn;
    }

    public void removeNextWaitingApn() {
        if ((mWaitingApns != null) && (!mWaitingApns.isEmpty())) {
            mWaitingApns.remove(0);
        }
    }

    public ArrayList<ApnSetting> getWaitingApns() {
        return mWaitingApns;
    }

    public void setState(DataConnectionTracker.State s) {
        if (DBG)
            log("setState: " + s + " for type " + mApnType + ", previous state:" + mState);

        mState = s;

        if (mState == DataConnectionTracker.State.FAILED) {
            if (mWaitingApns != null)
                mWaitingApns.clear(); // when teardown the connection and set to IDLE
        }
    }

    public DataConnectionTracker.State getState() {
        return mState;
    }

    public void setReason(String reason) {
        if (DBG)
            log("set reason as " + reason + ", for type " + mApnType + ",current state " + mState);
        mReason = reason;
    }

    public String getReason() {
        return mReason;
    }

    public void setReconnectIntent(PendingIntent intent) {
        if (DBG)
            log("set ReconnectIntent for type " + mApnType);
        mReconnectIntent = intent;
    }

    public PendingIntent getReconnectIntent() {
        return mReconnectIntent;
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "[ApnContext] " + s);
    }
}
