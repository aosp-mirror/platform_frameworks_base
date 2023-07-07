/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.app.job.UserVisibleJobSummary;

/**
 * IPC protocol to know about user-visible job activity.
 *
 * @hide
 */
oneway interface IUserVisibleJobObserver {
    /**
     * Notify the client of all changes to a user-visible jobs' state.
     * @param summary A token/summary that uniquely identifies and details a single running job
     * @param isRunning whether the job is currently running or not
     */
    void onUserVisibleJobStateChanged(in UserVisibleJobSummary summary, boolean isRunning);
}
