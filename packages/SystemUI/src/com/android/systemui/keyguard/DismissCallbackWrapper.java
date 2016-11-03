/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.keyguard;

import android.os.RemoteException;
import android.util.Log;

import com.android.internal.policy.IKeyguardDismissCallback;

/**
 * A light wrapper around {@link IKeyguardDismissCallback} handling {@link RemoteException}s.
 */
public class DismissCallbackWrapper {

    private static final String TAG = "DismissCallbackWrapper";

    private IKeyguardDismissCallback mCallback;

    public DismissCallbackWrapper(IKeyguardDismissCallback callback) {
        mCallback = callback;
    }

    public void notifyDismissError() {
        try {
            mCallback.onDismissError();
        } catch (RemoteException e) {
            Log.i(TAG, "Failed to call callback", e);
        }
    }

    public void notifyDismissCancelled() {
        try {
            mCallback.onDismissCancelled();
        } catch (RemoteException e) {
            Log.i(TAG, "Failed to call callback", e);
        }
    }

    public void notifyDismissSucceeded() {
        try {
            mCallback.onDismissSucceeded();
        } catch (RemoteException e) {
            Log.i(TAG, "Failed to call callback", e);
        }
    }
}
