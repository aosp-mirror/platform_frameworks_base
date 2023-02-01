/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.telecom;

import android.os.OutcomeReceiver;
import android.telecom.CallAttributes;
import android.telecom.CallControl;
import android.telecom.CallEventCallback;
import android.telecom.CallException;

import java.util.concurrent.Executor;

/**
 * @hide
 */
public class TransactionalCall {

    private final String mCallId;
    private final CallAttributes mCallAttributes;
    private final Executor mExecutor;
    private final OutcomeReceiver<CallControl, CallException> mPendingControl;
    private final CallEventCallback mCallEventCallback;
    private CallControl mCallControl;

    public TransactionalCall(String callId, CallAttributes callAttributes,
            Executor executor, OutcomeReceiver<CallControl, CallException>  pendingControl,
            CallEventCallback callEventCallback) {
        mCallId = callId;
        mCallAttributes = callAttributes;
        mExecutor = executor;
        mPendingControl = pendingControl;
        mCallEventCallback = callEventCallback;
    }

    public void setCallControl(CallControl callControl) {
        mCallControl = callControl;
    }

    public CallControl getCallControl() {
        return mCallControl;
    }

    public String getCallId() {
        return mCallId;
    }

    public CallAttributes getCallAttributes() {
        return mCallAttributes;
    }

    public Executor getExecutor() {
        return mExecutor;
    }

    public OutcomeReceiver<CallControl, CallException> getPendingControl() {
        return mPendingControl;
    }

    public CallEventCallback getCallEventCallback() {
        return mCallEventCallback;
    }
}
