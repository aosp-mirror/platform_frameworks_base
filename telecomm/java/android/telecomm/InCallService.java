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

package android.telecomm;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;

import com.android.internal.telecomm.IInCallAdapter;
import com.android.internal.telecomm.IInCallService;

/**
 * This service is implemented by any app that wishes to provide the user-interface for managing
 * phone calls. Telecomm binds to this service while there exists a live (active or incoming)
 * call, and uses it to notify the in-call app of any live and and recently disconnected calls.
 * TODO(santoscordon): Needs more/better description of lifecycle once the interface is better
 * defined.
 * TODO(santoscordon): What happens if two or more apps on a given device implement this interface?
 */
public abstract class InCallService extends Service {
    private static final int MSG_SET_IN_CALL_ADAPTER = 1;
    private static final int MSG_ADD_CALL = 2;
    private static final int MSG_SET_ACTIVE = 3;
    private static final int MSG_SET_DISCONNECTED = 4;
    private static final int MSG_SET_HOLD = 5;

    /** Default Handler used to consolidate binder method calls onto a single thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_IN_CALL_ADAPTER:
                    InCallAdapter adapter = new InCallAdapter((IInCallAdapter) msg.obj);
                    setInCallAdapter(adapter);
                    break;
                case MSG_ADD_CALL:
                    addCall((CallInfo) msg.obj);
                    break;
                case MSG_SET_ACTIVE:
                    setActive((String) msg.obj);
                    break;
                case MSG_SET_DISCONNECTED:
                    setDisconnected((String) msg.obj);
                    break;
                case MSG_SET_HOLD:
                    setOnHold((String) msg.obj);
                default:
                    break;
            }
        }
    };

    /** Manages the binder calls so that the implementor does not need to deal with it. */
    private final class InCallServiceBinder extends IInCallService.Stub {
        /** {@inheritDoc} */
        @Override
        public void setInCallAdapter(IInCallAdapter inCallAdapter) {
            mHandler.obtainMessage(MSG_SET_IN_CALL_ADAPTER, inCallAdapter).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void addCall(CallInfo callInfo) {
            mHandler.obtainMessage(MSG_ADD_CALL, callInfo).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setActive(String callId) {
            mHandler.obtainMessage(MSG_SET_ACTIVE, callId).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setDisconnected(String callId) {
            mHandler.obtainMessage(MSG_SET_DISCONNECTED, callId).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void setOnHold(String callId) {
            mHandler.obtainMessage(MSG_SET_HOLD, callId).sendToTarget();
        }
    }

    private final InCallServiceBinder mBinder;

    protected InCallService() {
        mBinder = new InCallServiceBinder();
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Provides the in-call app an adapter object through which to send call-commands such as
     * answering and rejecting incoming calls, disconnecting active calls, and putting calls in
     * special states (mute, hold, etc).
     *
     * @param inCallAdapter Adapter through which an in-call app can send call-commands to Telecomm.
     */
    protected abstract void setInCallAdapter(InCallAdapter inCallAdapter);

    /**
     * Indicates to the in-call app that a new call has been created and an appropriate
     * user-interface should be built and shown to notify the user. Information about the call
     * including its current state is passed in through the callInfo object.
     *
     * @param callInfo Information about the new call.
     */
     protected abstract void addCall(CallInfo callInfo);

    /**
     * Indicates to the in-call app that a call has moved to the {@link CallState#ACTIVE} state.
     *
     * @param callId The identifier of the call that became active.
     */
    protected abstract void setActive(String callId);

    /**
     * Indicates to the in-call app that a call has been moved to the
     * {@link CallState#DISCONNECTED} and the user should be notified.
     * TODO(santoscordon): Needs disconnect-cause either as a numberical constant, string or both
     * depending on what is ultimately needed to support all scenarios.
     *
     * @param callId The identifier of the call that was disconnected.
     */
    protected abstract void setDisconnected(String callId);

    /**
     * Indicates to the in-call app that a call has been moved to the
     * {@link android.telecomm.CallState#ON_HOLD} state and the user should be notified.
     *
     * @param callId The identifier of the call that was put on hold.
     */
    protected abstract void setOnHold(String callId);
}
