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

import android.print.PrinterId;
import android.print.PrintJobInfo;
import android.printservice.IPrintServiceClient;

/**
 * Top-level interface to a print service component.
 *
 * @hide
 */
oneway interface IPrintService {
    void setClient(IPrintServiceClient client);
    void requestCancelPrintJob(in PrintJobInfo printJobInfo);
    void onPrintJobQueued(in PrintJobInfo printJobInfo);

    void createPrinterDiscoverySession();
    void startPrinterDiscovery(in List<PrinterId> priorityList);
    void stopPrinterDiscovery();
    void validatePrinters(in List<PrinterId> printerIds);
    void startPrinterStateTracking(in PrinterId printerId);

    /**
     * Request the custom icon for a printer.
     *
     * @param printerId the id of the printer the icon should be loaded for
     * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon()
     */
    void requestCustomPrinterIcon(in PrinterId printerId);

    void stopPrinterStateTracking(in PrinterId printerId);
    void destroyPrinterDiscoverySession();
}
