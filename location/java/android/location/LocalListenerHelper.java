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

package android.location;

import com.android.internal.util.Preconditions;

import android.annotation.NonNull;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

/**
 * A base handler class to manage transport and local listeners.
 *
 * @hide
 */
abstract class LocalListenerHelper<TListener> {
    private final HashSet<TListener> mListeners = new HashSet<>();

    private final String mTag;
    private final Context mContext;

    protected LocalListenerHelper(Context context, String name) {
        Preconditions.checkNotNull(name);
        mContext = context;
        mTag = name;
    }

    public boolean add(@NonNull TListener listener) {
        Preconditions.checkNotNull(listener);
        synchronized (mListeners) {
            // we need to register with the service first, because we need to find out if the
            // service will actually support the request before we attempt anything
            if (mListeners.isEmpty()) {
                boolean registeredWithService;
                try {
                    registeredWithService = registerWithServer();
                } catch (RemoteException e) {
                    Log.e(mTag, "Error handling first listener.", e);
                    return false;
                }
                if (!registeredWithService) {
                    Log.e(mTag, "Unable to register listener transport.");
                    return false;
                }
            }
            if (mListeners.contains(listener)) {
                return true;
            }
            return mListeners.add(listener);
        }
    }

    public void remove(@NonNull TListener listener) {
        Preconditions.checkNotNull(listener);
        synchronized (mListeners) {
            boolean removed = mListeners.remove(listener);
            boolean isLastRemoved = removed && mListeners.isEmpty();
            if (isLastRemoved) {
                try {
                    unregisterFromServer();
                } catch (RemoteException e) {
                    Log.v(mTag, "Error handling last listener removal", e);
                }
            }
        }
    }

    protected abstract boolean registerWithServer() throws RemoteException;
    protected abstract void unregisterFromServer() throws RemoteException;

    protected interface ListenerOperation<TListener> {
        void execute(TListener listener) throws RemoteException;
    }

    protected Context getContext() {
        return mContext;
    }

    protected void foreach(ListenerOperation<TListener> operation) {
        Collection<TListener> listeners;
        synchronized (mListeners) {
            listeners = new ArrayList<>(mListeners);
        }
        for (TListener listener : listeners) {
            try {
                operation.execute(listener);
            } catch (RemoteException e) {
                Log.e(mTag, "Error in monitored listener.", e);
                // don't return, give a fair chance to all listeners to receive the event
            }
        }
    }
}
