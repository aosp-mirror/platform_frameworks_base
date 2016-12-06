/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.qs.external;

import android.os.IBinder;
import android.service.quicksettings.IQSService;
import android.service.quicksettings.IQSTileService;
import android.service.quicksettings.Tile;
import android.util.Log;


public class QSTileServiceWrapper {
    private static final String TAG = "IQSTileServiceWrapper";

    private final IQSTileService mService;

    public QSTileServiceWrapper(IQSTileService service) {
        mService = service;
    }

    public IBinder asBinder() {
        return mService.asBinder();
    }

    public boolean onTileAdded() {
        try {
            mService.onTileAdded();
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Caught exception from TileService", e);
            return false;
        }
    }

    public boolean onTileRemoved() {
        try {
            mService.onTileRemoved();
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Caught exception from TileService", e);
            return false;
        }
    }

    public boolean onStartListening() {
        try {
            mService.onStartListening();
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Caught exception from TileService", e);
            return false;
        }
    }

    public boolean onStopListening() {
        try {
            mService.onStopListening();
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Caught exception from TileService", e);
            return false;
        }
    }

    public boolean onClick(IBinder token) {
        try {
            mService.onClick(token);
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Caught exception from TileService", e);
            return false;
        }
    }

    public boolean onUnlockComplete() {
        try {
            mService.onUnlockComplete();
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Caught exception from TileService", e);
            return false;
        }
    }

    public IQSTileService getService() {
        return mService;
    }
}
