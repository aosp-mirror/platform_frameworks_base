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

package com.android.server.wm;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.window.ITaskFpsCallback;

import java.util.HashMap;

final class TaskFpsCallbackController {

    private final Context mContext;
    private final HashMap<ITaskFpsCallback, Long> mTaskFpsCallbacks;
    private final HashMap<ITaskFpsCallback, IBinder.DeathRecipient> mDeathRecipients;

    TaskFpsCallbackController(Context context) {
        mContext = context;
        mTaskFpsCallbacks = new HashMap<>();
        mDeathRecipients = new HashMap<>();
    }

    void registerListener(int taskId, ITaskFpsCallback callback) {
        if (mTaskFpsCallbacks.containsKey(callback)) {
            return;
        }

        final long nativeListener = nativeRegister(callback, taskId);
        mTaskFpsCallbacks.put(callback, nativeListener);

        final IBinder.DeathRecipient deathRecipient = () -> unregisterListener(callback);
        try {
            callback.asBinder().linkToDeath(deathRecipient, 0);
            mDeathRecipients.put(callback, deathRecipient);
        } catch (RemoteException e) {
            // ignore
        }
    }

    void unregisterListener(ITaskFpsCallback callback) {
        if (!mTaskFpsCallbacks.containsKey(callback)) {
            return;
        }

        callback.asBinder().unlinkToDeath(mDeathRecipients.get(callback), 0);
        mDeathRecipients.remove(callback);

        nativeUnregister(mTaskFpsCallbacks.get(callback));
        mTaskFpsCallbacks.remove(callback);
    }

    private static native long nativeRegister(ITaskFpsCallback callback, int taskId);
    private static native void nativeUnregister(long ptr);
}
