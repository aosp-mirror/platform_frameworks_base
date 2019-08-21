/**
 * Copyright (c) 2016, The Android Open Source Project
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

package android.os;

import android.os.IIncidentReportStatusListener;
import android.os.IncidentManager;
import android.os.IncidentReportArgs;

/**
  * Binder interface to report system health incidents.
  * {@hide}
  */
interface IIncidentManager {

    /**
     * Takes a report with the given args, reporting status to the optional listener.
     *
     * When the report is completed, the system report listener will be notified.
     */
    oneway void reportIncident(in IncidentReportArgs args);

    /**
     * Takes a report with the given args, reporting status to the optional listener.
     *
     * When the report is completed, the system report listener will be notified.
     */
    oneway void reportIncidentToStream(in IncidentReportArgs args,
            @nullable IIncidentReportStatusListener listener,
            FileDescriptor stream);

    /**
     * Takes a report with the given args, reporting status to the optional listener.
     * This should only be callable by dumpstate (enforced by callee).
     *
     * When the report is completed, the system report listener will be notified.
     */
    oneway void reportIncidentToDumpstate(FileDescriptor stream,
            @nullable IIncidentReportStatusListener listener);

    /**
     * Tell the incident daemon that the android system server is up and running.
     */
    oneway void systemRunning();

    /**
     * List the incident reports for the given ComponentName.  This is called
     * via IncidentCompanion, which validates that the package name matches
     * the caller.
     */
    List<String> getIncidentReportList(String pkg, String cls);

    /**
     * Get the IncidentReport object.
     */
    IncidentManager.IncidentReport getIncidentReport(String pkg, String cls, String id);

    /**
     * Reduce the refcount on this receiver. This is called
     * via IncidentCompanion, which validates that the package name matches
     * the caller.
     */
    void deleteIncidentReports(String pkg, String cls, String id);

    /**
     * Delete all incident reports for this package.
     */
    void deleteAllIncidentReports(String pkg);
}
