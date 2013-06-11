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

import android.os.ICancellationSignal;
import android.print.IPrintAdapter;
import android.print.IPrintClient;
import android.print.IPrinterDiscoveryObserver;
import android.print.PrinterId;
import android.print.PrintJobInfo;
import android.print.PrintAttributes;

/**
 * Interface for communication with the core print manager service.
 *
 * @hide
 */
interface IPrintManager {
    List<PrintJobInfo> getPrintJobs(int appId, int userId);
    PrintJobInfo getPrintJob(int printJobId, int appId, int userId);
    PrintJobInfo print(String printJobName, in IPrintClient client, in IPrintAdapter printAdapter,
            in PrintAttributes attributes, int appId, int userId);
    void cancelPrintJob(int printJobId, int appId, int userId);
    void onPrintJobQueued(in PrinterId printerId, in PrintJobInfo printJob);
    void startDiscoverPrinters(IPrinterDiscoveryObserver observer);
    void stopDiscoverPrinters();
}
