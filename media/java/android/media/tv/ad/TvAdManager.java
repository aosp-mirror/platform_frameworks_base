/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.media.tv.ad;

import android.annotation.FlaggedApi;
import android.annotation.SystemService;
import android.content.Context;
import android.media.tv.flags.Flags;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * Central system API to the overall client-side TV AD architecture, which arbitrates interaction
 * between applications and AD services.
 */
@FlaggedApi(Flags.FLAG_ENABLE_AD_SERVICE_FW)
@SystemService(Context.TV_AD_SERVICE)
public class TvAdManager {
    // TODO: implement more methods and unhide APIs.
    private static final String TAG = "TvAdManager";

    private final ITvAdManager mService;
    private final int mUserId;

    /** @hide */
    public TvAdManager(ITvAdManager service, int userId) {
        mService = service;
        mUserId = userId;
    }

    /**
     * The Session provides the per-session functionality of AD service.
     * @hide
     */
    public static final class Session {
        private final IBinder mToken;
        private final ITvAdManager mService;
        private final int mUserId;

        private Session(IBinder token, ITvAdManager service, int userId) {
            mToken = token;
            mService = service;
            mUserId = userId;
        }

        void startAdService() {
            if (mToken == null) {
                Log.w(TAG, "The session has been already released");
                return;
            }
            try {
                mService.startAdService(mToken, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
