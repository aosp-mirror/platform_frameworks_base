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
import android.graphics.drawable.Icon;
import android.os.ParcelFileDescriptor;
import android.print.IPrintSpoolerClient;
import android.print.IPrintSpoolerCallbacks;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.print.PrintAttributes;
import android.print.PrintJobId;
import android.print.PrintJobInfo;

/**
 * Interface for communication with the print spooler service.
 *
 * @see android.print.IPrintSpoolerCallbacks
 *
 * @hide
 */
oneway interface IPrintSpooler {
    void removeObsoletePrintJobs();
    void getPrintJobInfos(IPrintSpoolerCallbacks callback, in ComponentName componentName,
            int state, int appId, int sequence);
    void getPrintJobInfo(in PrintJobId printJobId, IPrintSpoolerCallbacks callback,
            int appId, int sequence);
    void createPrintJob(in PrintJobInfo printJob);
    void setPrintJobState(in PrintJobId printJobId, int status, String stateReason,
            IPrintSpoolerCallbacks callback, int sequence);

    /**
     * Set the progress of this print job
     *
     * @param printJobId The print job to update
     * @param progress The new progress
     */
    void setProgress(in PrintJobId printJobId, in float progress);

    /**
     * Set the status of this print job
     *
     * @param printJobId The print job to update
     * @param status The new status, can be null
     */
    void setStatus(in PrintJobId printJobId, in CharSequence status);

    /**
     * Set the status of this print job
     *
     * @param printJobId The print job to update
     * @param status The new status as a string resource
     * @param appPackageName App package name the resource belongs to
     */
    void setStatusRes(in PrintJobId printJobId, int status, in CharSequence appPackageName);

    /**
     * Handle that a custom icon for a printer was loaded.
     *
     * @param printerId the id of the printer the icon belongs to
     * @param icon the icon that was loaded
     * @param callbacks the callback to call once icon is stored in case
     * @param sequence the sequence number of the call
     * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon()
     */
    void onCustomPrinterIconLoaded(in PrinterId printerId, in Icon icon,
            in IPrintSpoolerCallbacks callbacks, in int sequence);

    /**
     * Get the custom icon for a printer. If the icon is not cached, the icon is
     * requested asynchronously. Once it is available the printer is updated.
     *
     * @param printerId the id of the printer the icon should be loaded for
     * @param callbacks the callback to call once icon is retrieved
     * @param sequence the sequence number of the call
     * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon()
     */
    void getCustomPrinterIcon(in PrinterId printerId,
            in IPrintSpoolerCallbacks callbacks, in int sequence);

    /**
     * Clear all state from the custom printer icon cache.
     *
     * @param callbacks the callback to call once cache is cleared
     * @param sequence the sequence number of the call
     */
    void clearCustomPrinterIconCache(in IPrintSpoolerCallbacks callbacks,
            in int sequence);

    void setPrintJobTag(in PrintJobId printJobId, String tag, IPrintSpoolerCallbacks callback,
            int sequence);
    void writePrintJobData(in ParcelFileDescriptor fd, in PrintJobId printJobId);
    void setClient(IPrintSpoolerClient client);
    void setPrintJobCancelling(in PrintJobId printJobId, boolean cancelling);

    /**
     * Remove all approved print services that are not in the given set.
     *
     * @param servicesToKeep The names of the services to keep
     */
    void pruneApprovedPrintServices(in List<ComponentName> servicesToKeep);
}
