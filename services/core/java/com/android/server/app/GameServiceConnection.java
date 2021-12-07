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

package com.android.server.app;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.games.GameService;
import android.service.games.IGameService;
import android.util.Slog;

final class GameServiceConnection {
    private static final String TAG = "GameServiceConnection";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final ComponentName mGameServiceComponent;
    private final int mUser;
    private boolean mIsBound;
    @Nullable
    private IGameService mGameService;
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) {
                Slog.d(TAG, "onServiceConnected to " + name + " for user(" + mUser + ")");
            }

            mGameService = IGameService.Stub.asInterface(service);
            try {
                mGameService.connected();
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException while calling ready", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) {
                Slog.d(TAG, "onServiceDisconnected to " + name);
            }

            mGameService = null;
        }
    };

    GameServiceConnection(Context context, ComponentName gameServiceComponent, int user) {
        mContext = context;
        mGameServiceComponent = gameServiceComponent;
        mUser = user;
    }

    public void connect() {
        if (mIsBound) {
            Slog.v(TAG, "Already bound, ignoring start.");
            return;
        }

        Intent intent = new Intent(GameService.SERVICE_INTERFACE);
        intent.setComponent(mGameServiceComponent);
        mIsBound = mContext.bindServiceAsUser(intent, mConnection,
                Context.BIND_AUTO_CREATE
                        | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS, new UserHandle(mUser));
        if (!mIsBound) {
            Slog.w(TAG, "Failed binding to game service " + mGameServiceComponent);
        }
    }

    public void disconnect() {
        try {
            if (mGameService != null) {
                mGameService.disconnected();
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in shutdown", e);
        }

        if (mIsBound) {
            mContext.unbindService(mConnection);
            mIsBound = false;
        }
    }
}
