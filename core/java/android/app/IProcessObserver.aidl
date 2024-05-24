/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.app;

/** {@hide} */
oneway interface IProcessObserver {
    /**
     * Invoked when an app process starts up.
     *
     * @param pid The pid of the process.
     * @param processUid The UID associated with the process.
     * @param packageUid The UID associated with the package.
     * @param packageName The name of the package.
     * @param processName The name of the process.
     */
    void onProcessStarted(int pid, int processUid, int packageUid,
                          @utf8InCpp String packageName, @utf8InCpp String processName);
    void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities);
    void onForegroundServicesChanged(int pid, int uid, int serviceTypes);
    void onProcessDied(int pid, int uid);
}
