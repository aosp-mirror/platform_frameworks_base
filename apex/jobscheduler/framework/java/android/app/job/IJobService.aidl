/**
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.job;

import android.app.job.JobParameters;
import android.app.job.JobWorkItem;

/**
 * Interface that the framework uses to communicate with application code that implements a
 * JobService.  End user code does not implement this interface directly; instead, the app's
 * service implementation will extend android.app.job.JobService.
 * {@hide}
 */
oneway interface IJobService {
    /** Begin execution of application's job. */
    @UnsupportedAppUsage
    void startJob(in JobParameters jobParams);
    /** Stop execution of application's job. */
    @UnsupportedAppUsage
    void stopJob(in JobParameters jobParams);
    /** Inform the job of a change in the network it should use. */
    void onNetworkChanged(in JobParameters jobParams);
    /** Update JS of how much data has been downloaded. */
    void getTransferredDownloadBytes(in JobParameters jobParams, in JobWorkItem jobWorkItem);
    /** Update JS of how much data has been uploaded. */
    void getTransferredUploadBytes(in JobParameters jobParams, in JobWorkItem jobWorkItem);
}
