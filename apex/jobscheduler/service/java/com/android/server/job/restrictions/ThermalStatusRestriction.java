/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.job.restrictions;

import android.app.job.JobParameters;
import android.content.Context;
import android.os.IThermalService;
import android.os.IThermalStatusListener;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Temperature;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerServiceDumpProto;
import com.android.server.job.controllers.JobStatus;

public class ThermalStatusRestriction extends JobRestriction {
    private static final String TAG = "ThermalStatusRestriction";

    private volatile boolean mIsThermalRestricted = false;

    public ThermalStatusRestriction(JobSchedulerService service) {
        super(service, JobParameters.REASON_DEVICE_THERMAL);
    }

    @Override
    public void onSystemServicesReady() {
        final IThermalService thermalService = IThermalService.Stub.asInterface(
                ServiceManager.getService(Context.THERMAL_SERVICE));
        if (thermalService != null) {
            try {
                thermalService.registerThermalStatusListener(new IThermalStatusListener.Stub() {
                    @Override
                    public void onStatusChange(int status) {
                        final boolean shouldBeActive = status >= Temperature.THROTTLING_SEVERE;
                        if (mIsThermalRestricted == shouldBeActive) {
                            return;
                        }
                        mIsThermalRestricted = shouldBeActive;
                        mService.onControllerStateChanged();
                    }
                });
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to register thermal callback.", e);
            }
        }
    }

    @Override
    public boolean isJobRestricted(JobStatus job) {
        return mIsThermalRestricted && job.hasConnectivityConstraint();
    }

    @Override
    public void dumpConstants(IndentingPrintWriter pw) {
        pw.print("In thermal throttling?: ");
        pw.print(mIsThermalRestricted);
    }

    @Override
    public void dumpConstants(ProtoOutputStream proto) {
        proto.write(JobSchedulerServiceDumpProto.IN_THERMAL, mIsThermalRestricted);
    }
}
