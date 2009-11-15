/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.accounts;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import java.util.ArrayList;
import java.util.Map;

import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

/**
 * A helper object that simplifies binding to Account Authenticators. It uses the
 * {@link AccountAuthenticatorCache} to find the component name of the authenticators,
 * allowing the user to bind by account name. It also allows multiple, simultaneous binds
 * to the same authenticator, with each bind call guaranteed to return either
 * {@link Callback#onConnected} or {@link Callback#onDisconnected} if the bind() call
 * itself succeeds, even if the authenticator is already bound internally.
 * @hide
 */
public class AuthenticatorBindHelper {
    private static final String TAG = "Accounts";
    private final Handler mHandler;
    private final Context mContext;
    private final int mMessageWhatConnected;
    private final int mMessageWhatDisconnected;
    private final Map<String, MyServiceConnection> mServiceConnections = Maps.newHashMap();
    private final Map<String, ArrayList<Callback>> mServiceUsers = Maps.newHashMap();
    private final AccountAuthenticatorCache mAuthenticatorCache;

    public AuthenticatorBindHelper(Context context,
            AccountAuthenticatorCache authenticatorCache, Handler handler,
            int messageWhatConnected, int messageWhatDisconnected) {
        mContext = context;
        mHandler = handler;
        mAuthenticatorCache = authenticatorCache;
        mMessageWhatConnected = messageWhatConnected;
        mMessageWhatDisconnected = messageWhatDisconnected;
    }

    public interface Callback {
        void onConnected(IBinder service);
        void onDisconnected();
    }

    public boolean bind(String authenticatorType, Callback callback) {
        // if the authenticator is connecting or connected then return true
        synchronized (mServiceConnections) {
            if (mServiceConnections.containsKey(authenticatorType)) {
                MyServiceConnection connection = mServiceConnections.get(authenticatorType);
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "service connection already exists for " + authenticatorType);
                }
                mServiceUsers.get(authenticatorType).add(callback);
                if (connection.mService != null) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "the service is connected, scheduling a connected message for "
                                + authenticatorType);
                    }
                    connection.scheduleCallbackConnectedMessage(callback);
                } else {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "the service is *not* connected, waiting for for "
                                + authenticatorType);
                    }
                }
                return true;
            }

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "there is no service connection for " + authenticatorType);
            }

            // otherwise find the component name for the authenticator and initiate a bind
            // if no authenticator or the bind fails then return false, otherwise return true
            AccountAuthenticatorCache.ServiceInfo<AuthenticatorDescription> authenticatorInfo =
                    mAuthenticatorCache.getServiceInfo(
                            AuthenticatorDescription.newKey(authenticatorType));
            if (authenticatorInfo == null) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "there is no authenticator for " + authenticatorType
                            + ", bailing out");
                }
                return false;
            }

            MyServiceConnection connection = new MyServiceConnection(authenticatorType);

            Intent intent = new Intent();
            intent.setAction("android.accounts.AccountAuthenticator");
            intent.setComponent(authenticatorInfo.componentName);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "performing bindService to " + authenticatorInfo.componentName);
            }
            if (!mContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "bindService to " + authenticatorInfo.componentName + " failed");
                }
                return false;
            }

            mServiceConnections.put(authenticatorType, connection);
            mServiceUsers.put(authenticatorType, Lists.newArrayList(callback));
            return true;
        }
    }

    public void unbind(Callback callbackToUnbind) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "unbinding callback " + callbackToUnbind);
        }
        synchronized (mServiceConnections) {
            for (Map.Entry<String, ArrayList<Callback>> entry : mServiceUsers.entrySet()) {
                final String authenticatorType = entry.getKey();
                final ArrayList<Callback> serviceUsers = entry.getValue();
                for (Callback callback : serviceUsers) {
                    if (callback == callbackToUnbind) {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "found callback in service" + authenticatorType);
                        }
                        serviceUsers.remove(callbackToUnbind);
                        if (serviceUsers.isEmpty()) {
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Log.v(TAG, "there are no more callbacks for service "
                                        + authenticatorType + ", unbinding service");
                            }
                            unbindFromServiceLocked(authenticatorType);
                        } else {
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Log.v(TAG, "leaving service " + authenticatorType
                                        + " around since there are still callbacks using it");
                            }
                        }
                        return;
                    }
                }
            }
            Log.e(TAG, "did not find callback " + callbackToUnbind + " in any of the services");
        }
    }

    /**
     * You must synchronized on mServiceConnections before calling this
     */
    private void unbindFromServiceLocked(String authenticatorType) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "unbindService from " + authenticatorType);
        }
        mContext.unbindService(mServiceConnections.get(authenticatorType));
        mServiceUsers.remove(authenticatorType);
        mServiceConnections.remove(authenticatorType);
    }

    private class ConnectedMessagePayload {
        public final IBinder mService;
        public final Callback mCallback;
        public ConnectedMessagePayload(IBinder service, Callback callback) {
            mService = service;
            mCallback = callback;
        }
    }

    private class MyServiceConnection implements ServiceConnection {
        private final String mAuthenticatorType;
        private IBinder mService = null;

        public MyServiceConnection(String authenticatorType) {
            mAuthenticatorType = authenticatorType;
        }

        public void onServiceConnected(ComponentName name, IBinder service) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "onServiceConnected for account type " + mAuthenticatorType);
            }
            // post a message for each service user to tell them that the service is connected
            synchronized (mServiceConnections) {
                mService = service;
                for (Callback callback : mServiceUsers.get(mAuthenticatorType)) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "the service became connected, scheduling a connected "
                                + "message for " + mAuthenticatorType);
                    }
                    scheduleCallbackConnectedMessage(callback);
                }
            }
        }

        private void scheduleCallbackConnectedMessage(Callback callback) {
            final ConnectedMessagePayload payload =
                    new ConnectedMessagePayload(mService, callback);
            mHandler.obtainMessage(mMessageWhatConnected, payload).sendToTarget();
        }

        public void onServiceDisconnected(ComponentName name) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "onServiceDisconnected for account type " + mAuthenticatorType);
            }
            // post a message for each service user to tell them that the service is disconnected,
            // and unbind from the service.
            synchronized (mServiceConnections) {
                final ArrayList<Callback> callbackList = mServiceUsers.get(mAuthenticatorType);
                if (callbackList != null) {
                    for (Callback callback : callbackList) {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "the service became disconnected, scheduling a "
                                    + "disconnected message for "
                                    + mAuthenticatorType);
                        }
                        mHandler.obtainMessage(mMessageWhatDisconnected, callback).sendToTarget();
                    }
                    unbindFromServiceLocked(mAuthenticatorType);
                }
            }
        }
    }

    boolean handleMessage(Message message) {
        if (message.what == mMessageWhatConnected) {
            ConnectedMessagePayload payload = (ConnectedMessagePayload)message.obj;
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "notifying callback " + payload.mCallback + " that it is connected");
            }
            payload.mCallback.onConnected(payload.mService);
            return true;
        } else if (message.what == mMessageWhatDisconnected) {
            Callback callback = (Callback)message.obj;
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "notifying callback " + callback + " that it is disconnected");
            }
            callback.onDisconnected();
            return true;
        } else {
            return false;
        }
    }
}
