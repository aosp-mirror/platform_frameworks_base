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

package com.android.server.job.controllers;

import com.android.server.job.JobSchedulerService;

/**
 * Controller that can also handle jobs in the
 * {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_RESTRICTED} bucket.
 */
public abstract class RestrictingController extends StateController {
    RestrictingController(JobSchedulerService service) {
        super(service);
    }

    /**
     * Start tracking a job that has been added to the
     * {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_RESTRICTED} bucket.
     */
    public abstract void startTrackingRestrictedJobLocked(JobStatus jobStatus);

    /**
     * Stop tracking a job that has been removed from the
     * {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_RESTRICTED} bucket.
     */
    public abstract void stopTrackingRestrictedJobLocked(JobStatus jobStatus);
}
