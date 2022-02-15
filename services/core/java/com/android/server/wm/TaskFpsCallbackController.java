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
import android.window.IOnFpsCallbackListener;

import java.util.HashMap;

final class TaskFpsCallbackController {

    private final Context mContext;
    private final HashMap<IOnFpsCallbackListener, Long> mTaskFpsCallbackListeners;
    private final HashMap<IOnFpsCallbackListener, IBinder.DeathRecipient> mDeathRecipients;

    TaskFpsCallbackController(Context context) {
        mContext = context;
        mTaskFpsCallbackListeners = new HashMap<>();
        mDeathRecipients = new HashMap<>();
    }

    void registerCallback(int taskId, IOnFpsCallbackListener listener) {
        if (mTaskFpsCallbackListeners.containsKey(listener)) {
            return;
        }

        final long nativeListener = nativeRegister(listener, taskId);
        mTaskFpsCallbackListeners.put(listener, nativeListener);

        final IBinder.DeathRecipient deathRecipient = () -> unregisterCallback(listener);
        try {
            listener.asBinder().linkToDeath(deathRecipient, 0);
            mDeathRecipients.put(listener, deathRecipient);
        } catch (RemoteException e) {
            // ignore
        }
    }

    void unregisterCallback(IOnFpsCallbackListener listener) {
        if (!mTaskFpsCallbackListeners.containsKey(listener)) {
            return;
        }

        listener.asBinder().unlinkToDeath(mDeathRecipients.get(listener), 0);
        mDeathRecipients.remove(listener);

        nativeUnregister(mTaskFpsCallbackListeners.get(listener));
        mTaskFpsCallbackListeners.remove(listener);
    }

    private static native long nativeRegister(IOnFpsCallbackListener listener, int taskId);
    private static native void nativeUnregister(long ptr);
}
