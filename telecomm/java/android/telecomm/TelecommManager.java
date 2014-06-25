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
 * limitations under the License.
 */

package android.telecomm;

import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.telecomm.ITelecommService;

/**
 * Provides access to Telecomm-related functionality.
 */
public class TelecommManager {
    private static final String TAG = "TelecommManager";
    private static final String TELECOMM_SERVICE_NAME = "telecomm";

    private final Context mContext;
    private final ITelecommService mService;

    /**
     * @hide
     */
    public TelecommManager(Context context, ITelecommService service) {
        Context appContext = context.getApplicationContext();
        if (appContext != null) {
            mContext = appContext;
        } else {
            mContext = context;
        }

        mService = service;
    }

    /**
     * @hide
     */
    public static TelecommManager from(Context context) {
        return (TelecommManager) context.getSystemService(Context.TELECOMM_SERVICE);
    }

    /**
     * @hide
     */
    @SystemApi
    public ComponentName getDefaultPhoneApp() {
        try {
            return getTelecommService().getDefaultPhoneApp();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException attempting to get the default phone app.", e);
        }
        return null;
    }

    private ITelecommService getTelecommService() {
        return ITelecommService.Stub.asInterface(ServiceManager.getService(TELECOMM_SERVICE_NAME));
    }
}
