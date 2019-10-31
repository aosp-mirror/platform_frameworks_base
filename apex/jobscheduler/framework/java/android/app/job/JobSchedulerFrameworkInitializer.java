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
 * limitations under the License.
 */

package android.app.job;

import android.app.JobSchedulerImpl;
import android.app.SystemServiceRegistry;
import android.content.Context;
import android.os.BatteryStats;

/**
 * This class needs to be pre-loaded by zygote.  This is where the job scheduler service wrapper
 * is registered.
 *
 * @hide
 */
public class JobSchedulerFrameworkInitializer {
    public static void initialize() {
        SystemServiceRegistry.registerStaticService(
                Context.JOB_SCHEDULER_SERVICE, JobScheduler.class,
                (b) -> new JobSchedulerImpl(IJobScheduler.Stub.asInterface(b)));

        BatteryStats.setJobStopReasons(JobParameters.JOB_STOP_REASON_CODES,
                JobParameters::getReasonName);
    }
}
