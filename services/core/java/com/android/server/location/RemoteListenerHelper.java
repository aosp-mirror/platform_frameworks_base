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
 * limitations under the License
 */

package com.android.server.location;

import com.android.internal.util.Preconditions;

import android.annotation.NonNull;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

/**
 * A helper class, that handles operations in remote listeners, and tracks for remote process death.
 */
abstract class RemoteListenerHelper<TListener extends IInterface> {
    private final String mTag;
    private final HashMap<IBinder, LinkedListener> mListenerMap =
            new HashMap<IBinder, LinkedListener>();

    protected RemoteListenerHelper(String name) {
        Preconditions.checkNotNull(name);
        mTag = name;
    }

    public boolean addListener(@NonNull TListener listener) {
        Preconditions.checkNotNull(listener, "Attempted to register a 'null' listener.");
        if (!isSupported()) {
            Log.e(mTag, "Refused to add listener, the feature is not supported.");
            return false;
        }

        IBinder binder = listener.asBinder();
        LinkedListener deathListener = new LinkedListener(listener);
        synchronized (mListenerMap) {
            if (mListenerMap.containsKey(binder)) {
                // listener already added
                return true;
            }

            try {
                binder.linkToDeath(deathListener, 0 /* flags */);
            } catch (RemoteException e) {
                // if the remote process registering the listener is already death, just swallow the
                // exception and continue
                Log.e(mTag, "Remote listener already died.", e);
                return false;
            }

            mListenerMap.put(binder, deathListener);
            if (mListenerMap.size() == 1) {
                if (!registerWithService()) {
                    Log.e(mTag, "RegisterWithService failed, listener will be removed.");
                    removeListener(listener);
                    return false;
                }
            }
        }

        return true;
    }

    public boolean removeListener(@NonNull TListener listener) {
        Preconditions.checkNotNull(listener, "Attempted to remove a 'null' listener.");
        if (!isSupported()) {
            Log.e(mTag, "Refused to remove listener, the feature is not supported.");
            return false;
        }

        IBinder binder = listener.asBinder();
        LinkedListener linkedListener;
        synchronized (mListenerMap) {
            linkedListener = mListenerMap.remove(binder);
            if (mListenerMap.isEmpty() && linkedListener != null) {
                unregisterFromService();
            }
        }

        if (linkedListener != null) {
            binder.unlinkToDeath(linkedListener, 0 /* flags */);
        }
        return true;
    }

    protected abstract boolean isSupported();
    protected abstract boolean registerWithService();
    protected abstract void unregisterFromService();

    protected interface ListenerOperation<TListener extends IInterface> {
        void execute(TListener listener) throws RemoteException;
    }

    protected void foreach(ListenerOperation operation) {
        Collection<LinkedListener> linkedListeners;
        synchronized (mListenerMap) {
            Collection<LinkedListener> values = mListenerMap.values();
            linkedListeners = new ArrayList<LinkedListener>(values);
        }

        for (LinkedListener linkedListener : linkedListeners) {
            TListener listener = linkedListener.getUnderlyingListener();
            try {
                operation.execute(listener);
            } catch (RemoteException e) {
                Log.e(mTag, "Error in monitored listener.", e);
                removeListener(listener);
            }
        }
    }

    private class LinkedListener implements IBinder.DeathRecipient {
        private final TListener mListener;

        public LinkedListener(@NonNull TListener listener) {
            mListener = listener;
        }

        @NonNull
        public TListener getUnderlyingListener() {
            return mListener;
        }

        @Override
        public void binderDied() {
            Log.d(mTag, "Remote Listener died: " + mListener);
            removeListener(mListener);
        }
    }
}
