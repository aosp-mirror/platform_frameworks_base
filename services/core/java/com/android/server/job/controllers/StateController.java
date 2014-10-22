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
 * limitations under the License
 */

package com.android.server.job.controllers;

import android.content.Context;

import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateChangedListener;

import java.io.PrintWriter;

/**
 * Incorporates shared controller logic between the various controllers of the JobManager.
 * These are solely responsible for tracking a list of jobs, and notifying the JM when these
 * are ready to run, or whether they must be stopped.
 */
public abstract class StateController {
    protected static final boolean DEBUG = false;
    protected Context mContext;
    protected StateChangedListener mStateChangedListener;

    public StateController(StateChangedListener stateChangedListener, Context context) {
        mStateChangedListener = stateChangedListener;
        mContext = context;
    }

    /**
     * Implement the logic here to decide whether a job should be tracked by this controller.
     * This logic is put here so the JobManger can be completely agnostic of Controller logic.
     * Also called when updating a task, so implementing controllers have to be aware of
     * preexisting tasks.
     */
    public abstract void maybeStartTrackingJob(JobStatus jobStatus);
    /**
     * Remove task - this will happen if the task is cancelled, completed, etc.
     */
    public abstract void maybeStopTrackingJob(JobStatus jobStatus);

    public abstract void dumpControllerState(PrintWriter pw);

}
