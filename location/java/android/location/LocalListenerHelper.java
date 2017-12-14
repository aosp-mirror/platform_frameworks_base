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

import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * A base handler class to manage transport and local listeners.
 *
 * @hide
 */
abstract class LocalListenerHelper<TListener> {
    private final HashMap<TListener, Handler> mListeners = new HashMap<>();

    private final String mTag;
    private final Context mContext;

    protected LocalListenerHelper(Context context, String name) {
        Preconditions.checkNotNull(name);
        mContext = context;
        mTag = name;
    }

    /**
     * Adds a {@param listener} to the list of listeners on which callbacks will be executed. The
     * execution will happen on the {@param handler} thread or alternatively in the callback thread
     * if a  {@code null} handler value is passed.
     */
    public boolean add(@NonNull TListener listener, Handler handler) {
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
            if (mListeners.containsKey(listener)) {
                return true;
            }
            mListeners.put(listener, handler);
            return true;
        }
    }

    public void remove(@NonNull TListener listener) {
        Preconditions.checkNotNull(listener);
        synchronized (mListeners) {
            boolean removed = mListeners.containsKey(listener);
            mListeners.remove(listener);
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

    private void executeOperation(ListenerOperation<TListener> operation, TListener listener) {
        try {
            operation.execute(listener);
        } catch (RemoteException e) {
            Log.e(mTag, "Error in monitored listener.", e);
            // don't return, give a fair chance to all listeners to receive the event
        }
    }

    protected void foreach(final ListenerOperation<TListener> operation) {
        Collection<Map.Entry<TListener, Handler>> listeners;
        synchronized (mListeners) {
            listeners = new ArrayList<>(mListeners.entrySet());
        }
        for (final Map.Entry<TListener, Handler> listener : listeners) {
            if (listener.getValue() == null) {
                executeOperation(operation, listener.getKey());
            } else {
                listener.getValue().post(new Runnable() {
                    @Override
                    public void run() {
                        executeOperation(operation, listener.getKey());
                    }
                });
            }
        }
    }
}
