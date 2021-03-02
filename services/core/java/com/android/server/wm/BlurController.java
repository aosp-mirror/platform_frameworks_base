/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.view.CrossWindowBlurListeners.CROSS_WINDOW_BLUR_SUPPORTED;

import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.view.ICrossWindowBlurEnabledListener;

final class BlurController {

    private final RemoteCallbackList<ICrossWindowBlurEnabledListener>
            mBlurEnabledListeners = new RemoteCallbackList<>();
    private final Object mLock = new Object();
    boolean mBlurEnabled;

    BlurController() {
        mBlurEnabled = CROSS_WINDOW_BLUR_SUPPORTED;
    }

    boolean registerCrossWindowBlurEnabledListener(ICrossWindowBlurEnabledListener listener) {
        if (listener == null) return false;
        mBlurEnabledListeners.register(listener);
        synchronized (mLock) {
            return mBlurEnabled;
        }
    }

    void unregisterCrossWindowBlurEnabledListener(ICrossWindowBlurEnabledListener listener) {
        if (listener == null) return;
        mBlurEnabledListeners.unregister(listener);
    }

    private void updateBlurEnabled() {
        // TODO: add other factors disabling blurs
        final boolean newEnabled = CROSS_WINDOW_BLUR_SUPPORTED;
        synchronized (mLock) {
            if (mBlurEnabled == newEnabled) {
                return;
            }
            mBlurEnabled = newEnabled;
            notifyBlurEnabledChanged(newEnabled);
        }
    }

    private void notifyBlurEnabledChanged(boolean enabled) {
        int i = mBlurEnabledListeners.beginBroadcast();
        while (i > 0) {
            i--;
            ICrossWindowBlurEnabledListener listener =
                    mBlurEnabledListeners.getBroadcastItem(i);
            try {
                listener.onCrossWindowBlurEnabledChanged(enabled);
            } catch (RemoteException e) {
            }
        }
        mBlurEnabledListeners.finishBroadcast();
    }


}
