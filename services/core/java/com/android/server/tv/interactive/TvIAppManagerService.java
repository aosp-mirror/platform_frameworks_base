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

package com.android.server.tv.interactive;

import android.content.Context;
import android.media.tv.interactive.ITvIAppManager;
import android.media.tv.interactive.ITvIAppSession;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.server.SystemService;
import com.android.server.utils.Slogf;

/**
 * This class provides a system service that manages interactive TV applications.
 */
public class TvIAppManagerService extends SystemService {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvIAppManagerService";

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public TvIAppManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Slogf.d(TAG, "onStart");
        }
        // TODO: make service name a constant in Context
        publishBinderService("tv_interactive_app", new BinderService());
    }

    private SessionState getSessionState(IBinder sessionToken) {
        // TODO: implement user state and get session from it.
        return null;
    }

    private final class BinderService extends ITvIAppManager.Stub {
        @Override
        public void startIApp(IBinder sessionToken, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "BinderService#start(userId=%d)", userId);
            }
            try {
                SessionState sessionState = getSessionState(sessionToken);
                if (sessionState != null && sessionState.mSession != null) {
                    sessionState.mSession.startIApp();
                }
            } catch (RemoteException e) {
                Slogf.e(TAG, "error in start", e);
            }
        }
    }

    private final class SessionState implements IBinder.DeathRecipient {
        private final IBinder mSessionToken;
        private ITvIAppSession mSession;

        private SessionState(IBinder sessionToken) {
            mSessionToken = sessionToken;
        }

        @Override
        public void binderDied() {
        }
    }
}
