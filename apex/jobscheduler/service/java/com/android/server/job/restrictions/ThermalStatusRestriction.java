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
import android.os.PowerManager;
import android.os.PowerManager.OnThermalStatusChangedListener;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerServiceDumpProto;
import com.android.server.job.controllers.JobStatus;

public class ThermalStatusRestriction extends JobRestriction {
    private static final String TAG = "ThermalStatusRestriction";

    private volatile boolean mIsThermalRestricted = false;

    private PowerManager mPowerManager;

    public ThermalStatusRestriction(JobSchedulerService service) {
        super(service, JobParameters.REASON_DEVICE_THERMAL);
    }

    @Override
    public void onSystemServicesReady() {
        mPowerManager = mService.getContext().getSystemService(PowerManager.class);
        // Use MainExecutor
        mPowerManager.addThermalStatusListener(new OnThermalStatusChangedListener() {
            @Override
            public void onThermalStatusChanged(int status) {
                // This is called on the main thread. Do not do any slow operations in it.
                // mService.onControllerStateChanged() will just post a message, which is okay.
                final boolean shouldBeActive = status >= PowerManager.THERMAL_STATUS_SEVERE;
                if (mIsThermalRestricted == shouldBeActive) {
                    return;
                }
                mIsThermalRestricted = shouldBeActive;
                mService.onControllerStateChanged();
            }
        });
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
