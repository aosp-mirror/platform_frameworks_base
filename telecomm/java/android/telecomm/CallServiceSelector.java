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
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.List;

/**
 * Allows for the organization of {@link CallService}s for outbound calls. Given a call and list of
 * {@link CallService} IDs, order the list in terms of priority and return it using
 * {@link #select(CallInfo, List)}. <br />
 * <br />
 * Also determine whether a call is switchable (can be moved between {@link CallService}s) or not
 * using {@link #isSwitchable(CallInfo)}.
 */
public abstract class CallServiceSelector extends Service {

    /** Manages the binder calls so that the implementor does not need to deal it. */
    private final class CallServiceSelectorBinder extends ICallServiceSelector.Stub {
        @Override
        public void isSwitchable(CallInfo callInfo, ICallSwitchabilityResponse response)
                throws RemoteException {
            // Ensure that we're running with the app's normal permission level
            long ident = Binder.clearCallingIdentity();
            try {
                response.setIsSwitchable(CallServiceSelector.this.isSwitchable(callInfo));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void select(CallInfo callInfo, List<CallServiceInfo> callServiceInfos,
                ICallServiceSelectionResponse response) throws RemoteException {
            // Ensure that we're running with the app's normal permission level
            long ident = Binder.clearCallingIdentity();
            try {
                response.setSelectedCallServiceInfos(
                        CallServiceSelector.this.select(callInfo, callServiceInfos));
            } finally {
                Binder.restoreCallingIdentity(ident);
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
     * Determines (and returns) whether the given call is switchable. That is, whether the call can
     * be moved to another {@link CallService} seamlessly.<br />
     * <br />
     * This method is not called on the UI thread and is safe to block.
     *
     * @param callInfo The call being potentially switched between {@link CallService}s.
     * @return Whether the given call is switchable or not.
     */
    protected abstract boolean isSwitchable(CallInfo callInfo);

    /**
     * Return a list of prioritized {@link CallService}s which should be used to complete the given
     * call.<br />
     * <br />
     * This method is not called on the UI thread and is safe to block.
     *
     * @param callInfo The call being placed using the {@link CallService}s.
     * @param callServiceInfos The details of the available {@link CallService}s with which to place
     *         the call.
     * @return A list of prioritized {@link CallServiceInfo}s to use to complete the given call.
     */
    protected abstract List<CallServiceInfo> select(
            CallInfo callInfo, List<CallServiceInfo> callServiceInfos);
}
