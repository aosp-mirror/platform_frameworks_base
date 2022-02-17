/**
 * Copyright (c) 2021, The Android Open Source Project
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

package android.tracing;

import android.os.ParcelFileDescriptor;

/*
 * Parameters for a trace report.
 *
 * See ITracingServiceProxy::reportTrace for more details.
 *
 * @hide
 */
parcelable TraceReportParams {
  // The package name containing the reporter service (see |reporterClassName|).
  String reporterPackageName;

  // The class name of the reporter service. The framework will bind to this service and pass the
  // trace fd and metadata to this class.
  // This class should be "trusted" (in practice this means being a priv_app + having DUMP and
  // USAGE_STATS permissions).
  String reporterClassName;

  // The file descriptor for the trace file. This will be an unlinked file fd (i.e. created
  // with O_TMPFILE); the intention is that reporter classes link this fd into a app-private
  // folder for reporting when conditions are right (e.g. charging, on unmetered networks etc).
  ParcelFileDescriptor fd;

  // The least-significant-bytes of the UUID of this trace.
  long uuidLsb;

  // The most-significant-bytes of the UUID of this trace.
  long uuidMsb;

  // Flag indicating whether, instead of passing the fd from the trace collector, to pass a
  // pipe fd from system_server and send the file over it.
  //
  // This flag is necessary because there is no good way to write a CTS test where a helper
  // priv_app (in terms of SELinux) is needed (this is because priv_apps are supposed to be
  // preinstalled on the system partition). By creating a pipe in system_server we work around
  // this restriction. Note that there is a maximum allowed file size if this flag is set
  // (see TracingServiceProxy). Further note that, even though SELinux may be worked around,
  // manifest (i.e. framework) permissions are still checked even if this flag is set.
  boolean usePipeForTesting;
}