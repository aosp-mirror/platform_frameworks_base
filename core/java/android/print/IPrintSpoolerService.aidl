/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.print;

import android.content.ComponentName;
import android.os.ParcelFileDescriptor;
import android.print.IPrintAdapter;
import android.print.IPrintClient;
import android.print.IPrintSpoolerServiceCallbacks;
import android.print.PrinterInfo;
import android.print.PrintAttributes;

/**
 * Interface for communication with the print spooler service.
 *
 * @see android.print.IPrintSpoolerServiceCallbacks
 *
 * @hide
 */
oneway interface IPrintSpoolerService {
    void getPrintJobs(IPrintSpoolerServiceCallbacks callback, in ComponentName componentName,
            int state, int appId, int sequence);
    void getPrintJob(int printJobId, IPrintSpoolerServiceCallbacks callback,
            int appId, int sequence);
    void createPrintJob(String printJobName, in IPrintClient client, in IPrintAdapter printAdapter,
            in PrintAttributes attributes, IPrintSpoolerServiceCallbacks callback, int appId,
            int sequence);
    void cancelPrintJob(int printJobId, IPrintSpoolerServiceCallbacks callback,
            int appId, int sequence);
    void setPrintJobState(int printJobId, int status, IPrintSpoolerServiceCallbacks callback,
            int sequence);
    void setPrintJobTag(int printJobId, String tag, IPrintSpoolerServiceCallbacks callback,
            int sequence);
    void writePrintJobData(in ParcelFileDescriptor fd, int printJobId);
}