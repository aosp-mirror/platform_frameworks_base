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

import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.Intent;

import java.util.Map;
import java.util.ArrayList;

import com.google.android.collect.Maps;
import com.google.android.collect.Lists;

/**
 * A helper object that simplifies binding to Account Authenticators. It uses the
 * {@link AccountAuthenticatorCache} to find the component name of the authenticators,
 * allowing the user to bind by account name. It also allows multiple, simultaneous binds
 * to the same authenticator, with each bind call guaranteed to return either
 * {@link Callback#onConnected} or {@link Callback#onDisconnected} if the bind() call
 * itself succeeds, even if the authenticator is already bound internally.
 */
public class AuthenticatorBindHelper {
    final private Handler mHandler;
    final private Context mContext;
    final private int mMessageWhatConnected;
    final private int mMessageWhatDisconnected;
    final private Map<String, MyServiceConnection> mServiceConnections = Maps.newHashMap();
    final private Map<String, ArrayList<Callback>> mServiceUsers = Maps.newHashMap();
    final private AccountAuthenticatorCache mAuthenticatorCache;

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
                mServiceUsers.get(authenticatorType).add(callback);
                return true;
            }

            // otherwise find the component name for the authenticator and initiate a bind
            // if no authenticator or the bind fails then return false, otherwise return true
            AccountAuthenticatorCache.AuthenticatorInfo authenticatorInfo =
                    mAuthenticatorCache.getAuthenticatorInfo(authenticatorType);
            if (authenticatorInfo == null) {
                return false;
            }

            MyServiceConnection connection = new MyServiceConnection(authenticatorType);

            Intent intent = new Intent();
            intent.setAction("android.accounts.AccountAuthenticator");
            intent.setComponent(authenticatorInfo.mComponentName);
            if (!mContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                return false;
            }

            mServiceConnections.put(authenticatorType, connection);
            mServiceUsers.put(authenticatorType, Lists.newArrayList(callback));
            return true;
        }
    }

    public void unbind(Callback callbackToUnbind) {
        synchronized (mServiceConnections) {
            for (Map.Entry<String, ArrayList<Callback>> entry : mServiceUsers.entrySet()) {
                final String authenticatorType = entry.getKey();
                final ArrayList<Callback> serviceUsers = entry.getValue();
                for (Callback callback : serviceUsers) {
                    if (callback == callbackToUnbind) {
                        serviceUsers.remove(callbackToUnbind);
                        if (serviceUsers.isEmpty()) {
                            unbindFromService(authenticatorType);
                        }
                        return;
                    }
                }
            }
        }
    }

    private void unbindFromService(String authenticatorType) {
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
        final private String mAuthenticatorType;

        public MyServiceConnection(String authenticatorType) {
            mAuthenticatorType = authenticatorType;
        }

        public void onServiceConnected(ComponentName name, IBinder service) {
            // post a message for each service user to tell them that the service is connected
            synchronized (mServiceConnections) {
                for (Callback callback : mServiceUsers.get(mAuthenticatorType)) {
                    final ConnectedMessagePayload payload =
                            new ConnectedMessagePayload(service, callback);
                    mHandler.obtainMessage(mMessageWhatConnected, payload).sendToTarget();
                }
            }
        }

        public void onServiceDisconnected(ComponentName name) {
            // post a message for each service user to tell them that the service is disconnected,
            // and unbind from the service.
            synchronized (mServiceConnections) {
                for (Callback callback : mServiceUsers.get(mAuthenticatorType)) {
                    mHandler.obtainMessage(mMessageWhatDisconnected, callback).sendToTarget();
                }
                unbindFromService(mAuthenticatorType);
            }
        }
    }

    boolean handleMessage(Message message) {
        if (message.what == mMessageWhatConnected) {
            ConnectedMessagePayload payload = (ConnectedMessagePayload)message.obj;
            payload.mCallback.onConnected(payload.mService);
            return true;
        } else if (message.what == mMessageWhatDisconnected) {
            Callback callback = (Callback)message.obj;
            callback.onDisconnected();
            return true;
        } else {
            return false;
        }
    }
}
