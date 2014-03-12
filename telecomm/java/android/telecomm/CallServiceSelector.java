/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.ICallServiceSelector;
import com.android.internal.telecomm.ICallServiceSelectionResponse;
import com.android.internal.telecomm.ICallSwitchabilityResponse;

import java.util.List;

/**
 * Allows for the organization of {@link CallService}s for outbound calls. Given a call and list of
 * {@link CallService} IDs, order the list in terms of priority and return it using
 * {@link #select(CallInfo, List, CallServiceSelectionResponse)}. <br />
 * <br />
 * Also determine whether a call is switchable (can be moved between {@link CallService}s) or not
 * using {@link #isSwitchable(CallInfo, CallSwitchabilityResponse)}.
 */
public abstract class CallServiceSelector extends Service {
    private static final String TAG = CallServiceSelector.class.getSimpleName();

    /**
     * Used to tell {@link #mHandler} to move the call to
     * {@link CallServiceSelector#isSwitchable(CallInfo, CallSwitchabilityResponse)} to the main
     * thread.
     */
    private static final int MSG_IS_SWITCHABLE = 1;

    /**
     * Used to tell {@link #mHandler} to move the call to
     * {@link #select(CallInfo, List, CallServiceSelectionResponse)} to the main thread.
     */
    private static final int MSG_SELECT_CALL_SERVICES = 2;

    /**
     * Listens for responses from the {@link CallServiceSelector} and passes them back through the
     * Binder interface. This must be called from
     * {@link CallServiceSelector#isSwitchable(CallInfo, CallSwitchabilityResponse)}.
     */
    public interface CallSwitchabilityResponse {
        /**
         * Mark a call as switchable (or not). This must be called by
         * {@link CallServiceSelector#isSwitchable(CallInfo, CallSwitchabilityResponse)}.
         *
         * @param isSwitchable Whether the call was switchable or not.
         */
        void setSwitchable(boolean isSwitchable);
    }

    /**
     * Listens for responses from the {@link CallServiceSelector} and passes them back through the
     * Binder interface. This must be called from
     * {@link CallServiceSelector#select(CallInfo, List, CallServiceSelectionResponse)}.
     */
    public interface CallServiceSelectionResponse {
        /**
         * Sets the prioritized {@link CallServiceDescriptor}s for the given {@link CallInfo}. This
         * must be called by
         * {@link CallServiceSelector#select(CallInfo, List, CallServiceSelectionResponse)}.
         *
         * @param callServices The prioritized {@link CallServiceDescriptor}s.
         */
        void setSelectedCallServices(List<CallServiceDescriptor> callServices);
    }

    /** Handler to move client-bound method calls to the main thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @SuppressWarnings("unchecked")
        @Override
        public void handleMessage(Message msg) {
            final SomeArgs args = (SomeArgs) msg.obj;
            try {
                switch (msg.what) {
                    case MSG_IS_SWITCHABLE:
                        isSwitchable((CallInfo) args.arg1, (CallSwitchabilityResponse) args.arg2);
                        break;
                    case MSG_SELECT_CALL_SERVICES:
                        select((CallInfo) args.arg1, (List<CallServiceDescriptor>) args.arg2,
                                (CallServiceSelectionResponse) args.arg3);
                        break;
                }
            } finally {
                if (args != null) {
                    args.recycle();
                }
            }
        }
    };

    /** Manages the binder calls so that the implementor does not need to deal with it. */
    private final class CallServiceSelectorBinder extends ICallServiceSelector.Stub
            implements CallSwitchabilityResponse, CallServiceSelectionResponse {
        private ICallSwitchabilityResponse mSwitchabilityResponse;
        private ICallServiceSelectionResponse mSelectionResponse;

        @Override
        public void isSwitchable(CallInfo callInfo, ICallSwitchabilityResponse response)
                throws RemoteException {
            mSwitchabilityResponse = response;
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callInfo;
            args.arg2 = this;
            mHandler.obtainMessage(MSG_IS_SWITCHABLE, args).sendToTarget();
        }

        @Override
        public void select(CallInfo callInfo, List<CallServiceDescriptor> descriptors,
                ICallServiceSelectionResponse response) throws RemoteException {
            mSelectionResponse = response;
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callInfo;
            args.arg2 = descriptors;
            args.arg3 = this;
            mHandler.obtainMessage(MSG_SELECT_CALL_SERVICES, args).sendToTarget();
        }

        @Override
        public void setSwitchable(boolean isSwitchable) {
            if (mSwitchabilityResponse != null) {
                try {
                    mSwitchabilityResponse.setIsSwitchable(isSwitchable);
                } catch (RemoteException e) {
                    Log.d(TAG, "Failed to set switchability", e);
                }
            } else {
                Log.wtf(TAG, "Switchability response object not set");
            }
        }

        @Override
        public void setSelectedCallServices(List<CallServiceDescriptor> callServices) {
            if (mSelectionResponse != null) {
                try {
                    mSelectionResponse.setSelectedCallServiceDescriptors(callServices);
                } catch (RemoteException e) {
                    Log.d(TAG, "Failed to set call services", e);
                }
            } else {
                Log.wtf(TAG, "Selector response object not set");
            }
        }
    }

    private final CallServiceSelectorBinder mBinder;

    protected CallServiceSelector() {
        mBinder = new CallServiceSelectorBinder();
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Determines whether the given call is switchable. That is, whether the call can be moved to
     * another {@link CallService} seamlessly. Once this is determined, the result is passed to the
     * given {@link CallSwitchabilityResponse} listener.<br />
     * <br />
     * This method is called on the main thread and is not safe to block.
     *
     * @param callInfo The call being potentially switched between {@link CallService}s.
     * @param response The {@link CallSwitchabilityResponse} listener to call back with the result.
     */
    protected abstract void isSwitchable(CallInfo callInfo, CallSwitchabilityResponse response);

    /**
     * Given a list of {@link CallServiceDescriptor}s, order them into a prioritized list and return
     * them through the given {@link CallServiceSelectionResponse} listener.<br />
     * <br />
     * This method is called on the UI thread and is not safe to block.
     *
     * @param callInfo The call being placed using the {@link CallService}s.
     * @param descriptors The descriptors of the available {@link CallService}s with which to place
     *            the call.
     * @param response The {@link CallServiceSelectionResponse} listener to call back with the
     *            result.
     */
    protected abstract void select(
            CallInfo callInfo,
            List<CallServiceDescriptor> descriptors,
            CallServiceSelectionResponse response);
}
