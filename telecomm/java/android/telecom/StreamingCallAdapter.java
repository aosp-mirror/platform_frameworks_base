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

package android.telecom;

import android.os.RemoteException;

import com.android.internal.telecom.IStreamingCallAdapter;

/**
 * Receives commands from {@link CallStreamingService} implementations which should be executed by
 * Telecom. When Telecom binds to a {@link CallStreamingService}, an instance of this class is given
 * to the general streaming app through which it can manipulate the streaming calls. Whe the general
 * streaming app is notified of new ongoing streaming calls, it can execute
 * {@link StreamingCall#setStreamingState(int)} for the ongoing streaming calls the user on the
 * receiver side would like to hold, unhold and disconnect.
 *
 * @hide
 */
public final class StreamingCallAdapter {
    private final IStreamingCallAdapter mAdapter;

    /**
     * {@hide}
     */
    public StreamingCallAdapter(IStreamingCallAdapter adapter) {
        mAdapter = adapter;
    }

    /**
     * Instruct telecom to change the state of the streaming call.
     *
     * @param state The streaming state to set
     */
    public void setStreamingState(@StreamingCall.StreamingCallState int state) {
        try {
            mAdapter.setStreamingState(state);
        } catch (RemoteException e) {
        }
    }
}
