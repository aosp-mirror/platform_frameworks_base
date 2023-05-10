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
    private final HashMap<IBinder, Long> mTaskFpsCallbacks;
    private final HashMap<IBinder, IBinder.DeathRecipient> mDeathRecipients;

    TaskFpsCallbackController(Context context) {
        mContext = context;
        mTaskFpsCallbacks = new HashMap<>();
        mDeathRecipients = new HashMap<>();
    }

    void registerListener(int taskId, ITaskFpsCallback callback) {
        if (callback == null) {
            return;
        }

        IBinder binder = callback.asBinder();
        if (mTaskFpsCallbacks.containsKey(binder)) {
            return;
        }

        final long nativeListener = nativeRegister(callback, taskId);
        mTaskFpsCallbacks.put(binder, nativeListener);

        final IBinder.DeathRecipient deathRecipient = () -> unregisterListener(callback);
        try {
            binder.linkToDeath(deathRecipient, 0);
            mDeathRecipients.put(binder, deathRecipient);
        } catch (RemoteException e) {
            // ignore
        }
    }

    void unregisterListener(ITaskFpsCallback callback) {
        if (callback == null) {
            return;
        }

        IBinder binder = callback.asBinder();
        if (!mTaskFpsCallbacks.containsKey(binder)) {
            return;
        }

        binder.unlinkToDeath(mDeathRecipients.get(binder), 0);
        mDeathRecipients.remove(binder);

        nativeUnregister(mTaskFpsCallbacks.get(binder));
        mTaskFpsCallbacks.remove(binder);
    }

    private static native long nativeRegister(ITaskFpsCallback callback, int taskId);
    private static native void nativeUnregister(long ptr);
}
