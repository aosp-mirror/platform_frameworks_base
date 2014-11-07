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
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;

/**
 * A helper class, that handles operations in remote listeners, and tracks for remote process death.
 */
abstract class RemoteListenerHelper<TListener extends IInterface> {
    protected static final int RESULT_SUCCESS = 0;
    protected static final int RESULT_NOT_AVAILABLE = 1;
    protected static final int RESULT_NOT_SUPPORTED = 2;
    protected static final int RESULT_GPS_LOCATION_DISABLED = 3;
    protected static final int RESULT_INTERNAL_ERROR = 4;

    private final Handler mHandler;
    private final String mTag;

    private final HashMap<IBinder, LinkedListener> mListenerMap = new HashMap<>();

    private boolean mIsRegistered;
    private boolean mHasIsSupported;
    private boolean mIsSupported;

    protected RemoteListenerHelper(Handler handler, String name) {
        Preconditions.checkNotNull(name);
        mHandler = handler;
        mTag = name;
    }

    public boolean addListener(@NonNull TListener listener) {
        Preconditions.checkNotNull(listener, "Attempted to register a 'null' listener.");
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
                // exception and return
                Log.v(mTag, "Remote listener already died.", e);
                return false;
            }
            mListenerMap.put(binder, deathListener);

            // update statuses we already know about, starting from the ones that will never change
            int result;
            if (!isAvailableInPlatform()) {
                result = RESULT_NOT_AVAILABLE;
            } else if (mHasIsSupported && !mIsSupported) {
                result = RESULT_NOT_SUPPORTED;
            } else if (!isGpsEnabled()) {
                result = RESULT_GPS_LOCATION_DISABLED;
            } else if (!tryRegister()) {
                // only attempt to register if GPS is enabled, otherwise we will register once GPS
                // becomes available
                result = RESULT_INTERNAL_ERROR;
            } else if (mHasIsSupported && mIsSupported) {
                result = RESULT_SUCCESS;
            } else {
                // at this point if the supported flag is not set, the notification will be sent
                // asynchronously in the future
                return true;
            }
            post(listener, getHandlerOperation(result));
        }
        return true;
    }

    public void removeListener(@NonNull TListener listener) {
        Preconditions.checkNotNull(listener, "Attempted to remove a 'null' listener.");
        IBinder binder = listener.asBinder();
        LinkedListener linkedListener;
        synchronized (mListenerMap) {
            linkedListener = mListenerMap.remove(binder);
            if (mListenerMap.isEmpty()) {
                tryUnregister();
            }
        }
        if (linkedListener != null) {
            binder.unlinkToDeath(linkedListener, 0 /* flags */);
        }
    }

    public void onGpsEnabledChanged(boolean enabled) {
        // handle first the sub-class implementation, so any error in registration can take
        // precedence
        handleGpsEnabledChanged(enabled);
        synchronized (mListenerMap) {
            if (!enabled) {
                tryUnregister();
                return;
            }
            if (mListenerMap.isEmpty()) {
                return;
            }
            if (tryRegister()) {
                // registration was successful, there is no need to update the state
                return;
            }
            ListenerOperation<TListener> operation = getHandlerOperation(RESULT_INTERNAL_ERROR);
            foreachUnsafe(operation);
        }
    }

    protected abstract boolean isAvailableInPlatform();
    protected abstract boolean isGpsEnabled();
    protected abstract boolean registerWithService();
    protected abstract void unregisterFromService();
    protected abstract ListenerOperation<TListener> getHandlerOperation(int result);
    protected abstract void handleGpsEnabledChanged(boolean enabled);

    protected interface ListenerOperation<TListener extends IInterface> {
        void execute(TListener listener) throws RemoteException;
    }

    protected void foreach(ListenerOperation<TListener> operation) {
        synchronized (mListenerMap) {
            foreachUnsafe(operation);
        }
    }

    protected void setSupported(boolean value, ListenerOperation<TListener> notifier) {
        synchronized (mListenerMap) {
            mHasIsSupported = true;
            mIsSupported = value;
            foreachUnsafe(notifier);
        }
    }

    private void foreachUnsafe(ListenerOperation<TListener> operation) {
        for (LinkedListener linkedListener : mListenerMap.values()) {
            post(linkedListener.getUnderlyingListener(), operation);
        }
    }

    private void post(TListener listener, ListenerOperation<TListener> operation) {
        if (operation != null) {
            mHandler.post(new HandlerRunnable(listener, operation));
        }
    }

    private boolean tryRegister() {
        if (!mIsRegistered) {
            mIsRegistered = registerWithService();
        }
        return mIsRegistered;
    }

    private void tryUnregister() {
        if (!mIsRegistered) {
            return;
        }
        unregisterFromService();
        mIsRegistered = false;
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

    private class HandlerRunnable implements Runnable {
        private final TListener mListener;
        private final ListenerOperation<TListener> mOperation;

        public HandlerRunnable(TListener listener, ListenerOperation<TListener> operation) {
            mListener = listener;
            mOperation = operation;
        }

        @Override
        public void run() {
            try {
                mOperation.execute(mListener);
            } catch (RemoteException e) {
                Log.v(mTag, "Error in monitored listener.", e);
            }
        }
    }
}
