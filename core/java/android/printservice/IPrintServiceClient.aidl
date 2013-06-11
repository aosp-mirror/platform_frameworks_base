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

package android.printservice;

import android.os.ParcelFileDescriptor;
import android.print.PrintJobInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;

/**
 * The top-level interface from a print service to the system.
 *
 * @hide
 */
interface IPrintServiceClient {
    List<PrintJobInfo> getPrintJobs();
    PrintJobInfo getPrintJob(int printJobId);
    boolean setPrintJobState(int printJobId, int status);
    boolean setPrintJobTag(int printJobId, String tag);
    oneway void writePrintJobData(in ParcelFileDescriptor fd, int printJobId);
    oneway void addDiscoveredPrinters(in List<PrinterInfo> printers);
    oneway void removeDiscoveredPrinters(in List<PrinterId> printers);
}
