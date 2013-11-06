/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.telephony;

import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;

import com.android.internal.telephony.IThirdPartyCallListener;

/**
 * Interface provided to {@link android.telephony.ThirdPartyCallService}. The service can use this
 * to notify the listener of changes to the call state.
 */
public class ThirdPartyCallListener {
    private final IThirdPartyCallListener mListener;

    // Call end reason.
    public static final int CALL_END_NORMAL = 1;
    public static final int CALL_END_INCOMING_MISSED = 2;
    public static final int CALL_END_OTHER = 3;

    public ThirdPartyCallListener(IThirdPartyCallListener listener) {
        mListener = listener;
    }

    /**
     * Called by the service when a call provider is available to perform the outgoing or incoming
     * call.
     */
    public void onCallProviderAttached(ThirdPartyCallProvider callProvider) {
        try {
            if (mListener != null) {
                mListener.onCallProviderAttached(callProvider.callback);
            }
        } catch (RemoteException e) {
        }
    }

    /**
     * Notifies the listener that ringing has started for this call.
     */
    public void onRingingStarted() {
        try {
            if (mListener != null) {
                mListener.onRingingStarted();
            }
        } catch (RemoteException e) {
        }
    }

    /**
     * Notifies the listener that the call has been successfully established.
     */
    public void onCallEstablished() {
        try {
            if (mListener != null) {
                mListener.onCallEstablished();
            }
        } catch (RemoteException e) {
        }
    }

    /**
     * Notifies the listener that the call has ended.
     */
    public void onCallEnded(int reason) {
        try {
            if (mListener != null) {
                mListener.onCallEnded(reason);
            }
        } catch (RemoteException e) {
        }
    }
}
