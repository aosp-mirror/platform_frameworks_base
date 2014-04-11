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

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.ICallServiceSelector;
import com.android.internal.telecomm.ICallServiceSelectorAdapter;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Allows for the organization of {@link CallService}s for outbound calls. Given a call and list of
 * {@link CallService} IDs, order the list in terms of priority and return it using
 * {@link #select(CallInfo, List)}.
 */
public abstract class CallServiceSelector extends Service {
    private static final int MSG_SET_CALL_SERVICE_SELECTOR_ADAPTER = 0;
    private static final int MSG_SELECT = 1;

    private final HashMap<String, CallInfo> mCalls = new HashMap<String, CallInfo>();

    /** Handler to move client-bound method calls to the main thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_CALL_SERVICE_SELECTOR_ADAPTER:
                    mAdapter = new CallServiceSelectorAdapter(
                            (ICallServiceSelectorAdapter) msg.obj);
                    onAdapterAttached(mAdapter);
                    break;
                case MSG_SELECT:
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        select((CallInfo) args.arg1, (List<CallServiceDescriptor>) args.arg2);
                    } finally {
                        args.recycle();
                    }
                    break;
            }
        }
    };

    /** Manages the binder calls so that the implementor does not need to deal with it. */
    private final class CallServiceSelectorBinder extends ICallServiceSelector.Stub {
        @Override
        public void setCallServiceSelectorAdapter(ICallServiceSelectorAdapter adapter) {
            mHandler.obtainMessage(MSG_SET_CALL_SERVICE_SELECTOR_ADAPTER, adapter)
                    .sendToTarget();
        }

        @Override
        public void select(CallInfo callInfo, List<CallServiceDescriptor> descriptors) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callInfo;
            args.arg2 = descriptors;
            mHandler.obtainMessage(MSG_SELECT, args).sendToTarget();
        }

        @Override
        public void onCallUpdated(CallInfo callInfo) {
            mCalls.put(callInfo.getId(), callInfo);
        }

        @Override
        public void onCallRemoved(String callId) {
            mCalls.remove(callId);
        }
    }

    private final CallServiceSelectorBinder mBinder;

    private CallServiceSelectorAdapter mAdapter = null;

    protected CallServiceSelector() {
        mBinder = new CallServiceSelectorBinder();
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Returns a list of all calls managed by this selector.
     */
    protected final Collection<CallInfo> getCalls() {
        return Collections.unmodifiableCollection(mCalls.values());
    }

    /**
     * @return The attached {@link CallServiceSelectorAdapter} if attached, or null otherwise.
     */
    protected final CallServiceSelectorAdapter getAdapter() {
        return mAdapter;
    }

    /**
     * Cancel the outgoing call. Any subsequent calls to {@link #select(CallInfo, List)} will be
     * ignored.
     *
     * @param callInfo The call to canceled.
     */
    protected final void cancelOutgoingCall(CallInfo callInfo) {
        getAdapter().cancelOutgoingCall(callInfo.getId());
    }

    /**
     * Lifecycle callback which is called when this {@link CallServiceSelector} has been attached
     * to a {@link CallServiceSelectorAdapter}, indicating {@link #getAdapter()} is now safe to use.
     *
     * @param adapter The adapter now attached to this call service selector.
     */
    protected void onAdapterAttached(CallServiceSelectorAdapter adapter) {
    }

    /**
     * Given a list of {@link CallServiceDescriptor}s, order them into a prioritized list and return
     * them through
     * {@link CallServiceSelectorAdapter#setSelectedCallServices(String,List)}.
     *
     * @param callInfo The call being placed using the {@link CallService}s.
     * @param descriptors The descriptors of the available {@link CallService}s with which to place
     *            the call.
     */
    protected abstract void select(CallInfo callInfo, List<CallServiceDescriptor> descriptors);
}
