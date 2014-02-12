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
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Pair;

import com.android.internal.telephony.IThirdPartyCallListener;
import com.android.internal.telephony.IThirdPartyCallService;

/**
 * Interface provided by a service to start outgoing calls and attach to incoming calls.
 */
public class ThirdPartyCallService {
    private static final int MSG_OUTGOING_CALL_INITIATE = 1;
    private static final int MSG_INCOMING_CALL_ATTACH = 2;

    /**
     * Call to start a new outgoing call.
     */
    public void outgoingCallInitiate(ThirdPartyCallListener listener, String number) {
        // default implementation empty
    }

    /**
     * Call to attach to an incoming call. This is in response to a call to {@link
     * android.telephony.TelephonyManager#newIncomingThirdPartyCall newIncomingThirdPartyCall}.
     */
    public void incomingCallAttach(ThirdPartyCallListener listener, String callId) {
        // default implementation empty
    }

    /**
     * Returns an IBinder instance that can returned from the service's onBind function.
     */
    public IBinder getBinder() {
        return callback;
    }

    private final IThirdPartyCallService.Stub callback = new IThirdPartyCallService.Stub() {
        @Override
        public void outgoingCallInitiate(IThirdPartyCallListener listener, String number) {
            Rlog.w("ThirdPartyPhone", "ThirdPartyCallService.IThirdPartyCallService.out");
            Message.obtain(mHandler, MSG_OUTGOING_CALL_INITIATE,
                    Pair.create(listener, number)).sendToTarget();
        }

        @Override
        public void incomingCallAttach(IThirdPartyCallListener listener, String callId) {
            Rlog.w("ThirdPartyPhone", "ThirdPartyCallService.IThirdPartyCallService.in");
            Message.obtain(mHandler, MSG_INCOMING_CALL_ATTACH,
                    Pair.create(listener, callId)).sendToTarget();
        }
    };

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            Rlog.w("ThirdPartyPhone", "ThirdPartyCallService.handleMessage: " + msg.what);
            switch (msg.what) {
                case MSG_OUTGOING_CALL_INITIATE: {
                    Rlog.w("ThirdPartyPhone", "ThirdPartyCallService.handleMessage out");
                    Pair<IThirdPartyCallListener, String> pair =
                            (Pair<IThirdPartyCallListener, String>) msg.obj;
                    ThirdPartyCallListener listener = new ThirdPartyCallListener(pair.first);
                    outgoingCallInitiate(listener, pair.second);
                    break;
                }
                case MSG_INCOMING_CALL_ATTACH: {
                    Rlog.w("ThirdPartyPhone", "ThirdPartyCallService.handleMessage in");
                    Pair<IThirdPartyCallListener, String> pair =
                            (Pair<IThirdPartyCallListener, String>) msg.obj;
                    ThirdPartyCallListener listener = new ThirdPartyCallListener(pair.first);
                    incomingCallAttach(listener, pair.second);
                    break;
                }
            }
        }
    };
}
