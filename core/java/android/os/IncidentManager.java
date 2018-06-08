/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.os;

import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.util.Slog;

/**
 * Class to take an incident report.
 *
 * @hide
 */
@SystemApi
@TestApi
@SystemService(Context.INCIDENT_SERVICE)
public class IncidentManager {
    private static final String TAG = "IncidentManager";

    private final Context mContext;

    private IIncidentManager mService;

    /**
     * @hide
     */
    public IncidentManager(Context context) {
        mContext = context;
    }

    /**
     * Take an incident report and put it in dropbox.
     */
    @RequiresPermission(allOf = {
            android.Manifest.permission.DUMP,
            android.Manifest.permission.PACKAGE_USAGE_STATS
    })
    public void reportIncident(IncidentReportArgs args) {
        reportIncidentInternal(args);
    }

    private class IncidentdDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            synchronized (this) {
                mService = null;
            }
        }
    }

    private void reportIncidentInternal(IncidentReportArgs args) {
        try {
            final IIncidentManager service = getIIncidentManagerLocked();
            if (service == null) {
                Slog.e(TAG, "reportIncident can't find incident binder service");
                return;
            }
            service.reportIncident(args);
        } catch (RemoteException ex) {
            Slog.e(TAG, "reportIncident failed", ex);
        }
    }

    private IIncidentManager getIIncidentManagerLocked() throws RemoteException {
        if (mService != null) {
            return mService;
        }

        synchronized (this) {
            if (mService != null) {
                return mService;
            }
            mService = IIncidentManager.Stub.asInterface(
                ServiceManager.getService(Context.INCIDENT_SERVICE));
            if (mService != null) {
                mService.asBinder().linkToDeath(new IncidentdDeathRecipient(), 0);
            }
            return mService;
        }
    }

}

