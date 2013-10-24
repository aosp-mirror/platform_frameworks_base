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

import com.android.internal.telephony.IThirdPartyCallProvider;

/**
 * Interface sent to {@link android.telephony.ThirdPartyCallListener#onCallProviderAttached
 * onCallProviderAttached}. This is used to control an outgoing or an incoming call.
 */
public class ThirdPartyCallProvider {
    private static final int MSG_MUTE = 1;
    private static final int MSG_HANGUP = 2;
    private static final int MSG_INCOMING_CALL_ACCEPT = 3;

    /**
     * Mutes or unmutes the call.
     */
    public void mute(boolean shouldMute) {
        // default implementation empty
    }

    /**
     * Ends the current call. If this is an unanswered incoming call then the call is rejected.
     */
    public void hangup() {
        // default implementation empty
    }

   /**
     * Accepts the incoming call.
     */
    public void incomingCallAccept() {
        // default implementation empty
    }

    final IThirdPartyCallProvider callback = new IThirdPartyCallProvider.Stub() {
        @Override
        public void mute(boolean shouldMute) {
            Message.obtain(mHandler, MSG_MUTE, shouldMute ? 1 : 0, 0).sendToTarget();
        }

        @Override
        public void hangup() {
            Message.obtain(mHandler, MSG_HANGUP).sendToTarget();
        }

        @Override
        public void incomingCallAccept() {
            Message.obtain(mHandler, MSG_INCOMING_CALL_ACCEPT).sendToTarget();
        }
    };

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_MUTE:
                    mute(msg.arg1 != 0);
                    break;
                case MSG_HANGUP:
                    hangup();
                    break;
                case MSG_INCOMING_CALL_ACCEPT:
                    incomingCallAccept();
                    break;
            }
        }
    };
}
