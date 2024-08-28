/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.supervision;

import android.annotation.SystemService;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.RemoteException;

/**
 * Service for handling parental supervision.
 *
 * @hide
 */
@SystemService(Context.SUPERVISION_SERVICE)
public class SupervisionManager {
    private final Context mContext;
    private final ISupervisionManager mService;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public SupervisionManager(Context context, ISupervisionManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns whether the device is supervised.
     *
     * @hide
     */
    public boolean isSupervisionEnabled() {
        try {
            return mService.isSupervisionEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


}
