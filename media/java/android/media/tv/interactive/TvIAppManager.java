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

package android.media.tv.interactive;

import android.annotation.SystemService;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * Central system API to the overall TV interactive application framework (TIAF) architecture, which
 * arbitrates interaction between applications and interactive apps.
 * @hide
 */
@SystemService("tv_interactive_app")
public final class TvIAppManager {
    private static final String TAG = "TvIAppManager";

    private final ITvIAppManager mService;
    private final int mUserId;

    public TvIAppManager(ITvIAppManager service, int userId) {
        mService = service;
        mUserId = userId;
    }

    /**
     * The Session provides the per-session functionality of interactive app.
     */
    public static final class Session {
        private final IBinder mToken;
        private final ITvIAppManager mService;
        private final int mUserId;

        private Session(IBinder token, ITvIAppManager service, int userId) {
            mToken = token;
            mService = service;
            mUserId = userId;
        }

        void startIApp() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.startIApp(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
