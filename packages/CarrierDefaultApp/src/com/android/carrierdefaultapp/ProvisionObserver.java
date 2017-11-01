/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.carrierdefaultapp;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.telephony.TelephonyIntents;

/**
 * Service to run {@link android.app.job.JobScheduler} job.
 * Service to monitor when there is a change to conent URI
 * {@link android.provider.Settings.Global#DEVICE_PROVISIONED DEVICE_PROVISIONED}
 */
public class ProvisionObserver extends JobService {

    private static final String TAG = ProvisionObserver.class.getSimpleName();
    public static final int PROVISION_OBSERVER_REEVALUATION_JOB_ID = 1;
    // minimum & maximum update delay TBD
    private static final int CONTENT_UPDATE_DELAY_MS = 100;
    private static final int CONTENT_MAX_DELAY_MS = 200;

    @Override
    public boolean onStartJob(JobParameters jobParameters) {
        switch (jobParameters.getJobId()) {
            case PROVISION_OBSERVER_REEVALUATION_JOB_ID:
                if (isProvisioned(this)) {
                    Log.d(TAG, "device provisioned, force network re-evaluation");
                    final ConnectivityManager connMgr = ConnectivityManager.from(this);
                    Network[] info = connMgr.getAllNetworks();
                    for (Network nw : info) {
                        final NetworkCapabilities nc = connMgr.getNetworkCapabilities(nw);
                        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                                && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                            // force connectivity re-evaluation to assume skipped carrier actions.
                            // one of the following calls will match the last evaluation.
                            connMgr.reportNetworkConnectivity(nw, true);
                            connMgr.reportNetworkConnectivity(nw, false);
                            break;
                        }
                    }
                }
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters jobParameters) {
        return false;
    }

    // Returns true if the device is not provisioned yet (in setup wizard), false otherwise
    private static boolean isProvisioned(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) == 1;
    }

    /**
     * Static utility function to schedule a job to execute upon the change of content URI
     * {@link android.provider.Settings.Global#DEVICE_PROVISIONED DEVICE_PROVISIONED}.
     * @param context The context used to retrieve the {@link ComponentName} and system services
     * @return true carrier actions are deferred due to phone provisioning process, false otherwise
     */
    public static boolean isDeferredForProvision(Context context, Intent intent) {
        if (isProvisioned(context)) {
            return false;
        }
        int jobId;
        switch(intent.getAction()) {
            case TelephonyIntents.ACTION_CARRIER_SIGNAL_REDIRECTED:
                jobId = PROVISION_OBSERVER_REEVALUATION_JOB_ID;
                break;
            default:
                return false;
        }
        final JobScheduler jobScheduler =  (JobScheduler) context.getSystemService(
                Context.JOB_SCHEDULER_SERVICE);
        final JobInfo job = new JobInfo.Builder(jobId,
                new ComponentName(context, ProvisionObserver.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(
                        Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED), 0))
                .setTriggerContentUpdateDelay(CONTENT_UPDATE_DELAY_MS)
                .setTriggerContentMaxDelay(CONTENT_MAX_DELAY_MS)
                .build();
        jobScheduler.schedule(job);
        return true;
    }
}
