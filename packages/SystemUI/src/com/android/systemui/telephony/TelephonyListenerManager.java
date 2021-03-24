/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.telephony;

import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener;
import android.telephony.TelephonyCallback.CallStateListener;
import android.telephony.TelephonyCallback.ServiceStateListener;
import android.telephony.TelephonyManager;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Wrapper around {@link TelephonyManager#listen(PhoneStateListener, int)}.
 *
 * The TelephonyManager complains if too many places in code register a listener. This class
 * encapsulates SystemUI's usage of this function, reducing it down to a single listener.
 *
 * See also
 * {@link TelephonyManager#registerTelephonyCallback(Executor, android.telephony.TelephonyCallback)}
 */
@SysUISingleton
public class TelephonyListenerManager {
    private final TelephonyManager mTelephonyManager;
    private final Executor mExecutor;
    private final TelephonyCallback mTelephonyCallback;

    private boolean mListening = false;

    @Inject
    public TelephonyListenerManager(TelephonyManager telephonyManager, @Main Executor executor,
            TelephonyCallback telephonyCallback) {
        mTelephonyManager = telephonyManager;
        mExecutor = executor;
        mTelephonyCallback = telephonyCallback;
    }

    /** */
    public void addActiveDataSubscriptionIdListener(ActiveDataSubscriptionIdListener listener) {
        mTelephonyCallback.addActiveDataSubscriptionIdListener(listener);
        updateListening();
    }

    /** */
    public void removeActiveDataSubscriptionIdListener(ActiveDataSubscriptionIdListener listener) {
        mTelephonyCallback.removeActiveDataSubscriptionIdListener(listener);
        updateListening();
    }

    /** */
    public void addCallStateListener(CallStateListener listener) {
        mTelephonyCallback.addCallStateListener(listener);
        updateListening();
    }

    /** */
    public void removeCallStateListener(CallStateListener listener) {
        mTelephonyCallback.removeCallStateListener(listener);
        updateListening();
    }

    /** */
    public void addServiceStateListener(ServiceStateListener listener) {
        mTelephonyCallback.addServiceStateListener(listener);
        updateListening();
    }

    /** */
    public void removeServiceStateListener(ServiceStateListener listener) {
        mTelephonyCallback.removeServiceStateListener(listener);
        updateListening();
    }


    private void updateListening() {
        if (!mListening && mTelephonyCallback.hasAnyListeners()) {
            mListening = true;
            mTelephonyManager.registerTelephonyCallback(mExecutor, mTelephonyCallback);
        } else if (mListening && !mTelephonyCallback.hasAnyListeners()) {
            mTelephonyManager.unregisterTelephonyCallback(mTelephonyCallback);
            mListening = false;
        }
    }
}
