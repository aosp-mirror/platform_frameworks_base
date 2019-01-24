/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import android.util.proto.ProtoOutputStream;

/**
 * Interface used by the owner/creator of a process that owns windows to listen to changes from the
 * WM side.
 * @see WindowProcessController
 */
public interface WindowProcessListener {

    /** Clear the profiler record if we are currently profiling this process. */
    void clearProfilerIfNeeded();

    /** Update the service connection for this process based on activities it might have. */
    void updateServiceConnectionActivities();

    /** Set or clear flag that we would like to clean-up UI resources for this process. */
    void setPendingUiClean(boolean pendingUiClean);

    /**
     * Set flag that we would like to clean-up UI resources for this process and force new process
     * state.
     */
    void setPendingUiCleanAndForceProcessStateUpTo(int newState);

    /** Update the process information. */
    void updateProcessInfo(boolean updateServiceConnectionActivities, boolean updateLru,
            boolean activityChange, boolean updateOomAdj);

    /**
     * Returns true if the process is removed and we should completely clean up the related records
     * belonging to this process.
     */
    boolean isRemoved();

    /** Returns the total time (in milliseconds) spent executing in both user and system code. */
    long getCpuTime();

    /** Clears the waiting to kill reason for this process. */
    void clearWaitingToKill();

    /** Adds the package to the process. */
    void addPackage(String pkg, long versionCode);

    /** Called when we are in the process on starting an activity. */
    void onStartActivity(int topProcessState, boolean setProfileProc);

    /** App died :(...oh well */
    void appDied();
    void writeToProto(ProtoOutputStream proto, long fieldId);
}
