/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.net.INetworkPolicyListener;
import android.net.NetworkPolicyManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import com.android.systemui.statusbar.policy.DataSaverController.Listener;

import java.util.ArrayList;

public class DataSaverControllerImpl implements DataSaverController {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<Listener> mListeners = new ArrayList<>();
    private final NetworkPolicyManager mPolicyManager;

    public DataSaverControllerImpl(Context context) {
        mPolicyManager = NetworkPolicyManager.from(context);
    }

    private void handleRestrictBackgroundChanged(boolean isDataSaving) {
        synchronized (mListeners) {
            for (int i = 0; i < mListeners.size(); i++) {
                mListeners.get(i).onDataSaverChanged(isDataSaving);
            }
        }
    }

    public void addCallback(Listener listener) {
        synchronized (mListeners) {
            mListeners.add(listener);
            if (mListeners.size() == 1) {
                mPolicyManager.registerListener(mPolicyListener);
            }
        }
        listener.onDataSaverChanged(isDataSaverEnabled());
    }

    public void removeCallback(Listener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
            if (mListeners.size() == 0) {
                mPolicyManager.unregisterListener(mPolicyListener);
            }
        }
    }

    public boolean isDataSaverEnabled() {
        return mPolicyManager.getRestrictBackground();
    }

    public void setDataSaverEnabled(boolean enabled) {
        mPolicyManager.setRestrictBackground(enabled);
        try {
            mPolicyListener.onRestrictBackgroundChanged(enabled);
        } catch (RemoteException e) {
        }
    }

    private final INetworkPolicyListener mPolicyListener = new NetworkPolicyManager.Listener() {
        @Override
        public void onRestrictBackgroundChanged(final boolean isDataSaving) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleRestrictBackgroundChanged(isDataSaving);
                }
            });
        }
    };

}
