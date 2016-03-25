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

import android.graphics.drawable.Icon;
import android.os.ParcelFileDescriptor;
import android.print.PrintJobInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.print.PrintJobId;
import android.content.pm.ParceledListSlice;

/**
 * The top-level interface from a print service to the system.
 *
 * @hide
 */
interface IPrintServiceClient {
    List<PrintJobInfo> getPrintJobInfos();
    PrintJobInfo getPrintJobInfo(in PrintJobId printJobId);
    boolean setPrintJobState(in PrintJobId printJobId, int state, String error);
    boolean setPrintJobTag(in PrintJobId printJobId, String tag);
    oneway void writePrintJobData(in ParcelFileDescriptor fd, in PrintJobId printJobId);

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
     * @param appPackageName The app package name the string belongs to
     */
    void setStatusRes(in PrintJobId printJobId, int status, in CharSequence appPackageName);

    void onPrintersAdded(in ParceledListSlice printers);
    void onPrintersRemoved(in ParceledListSlice printerIds);

    /**
     * Handle that a custom icon for a printer was loaded.
     *
     * @param printerId the id of the printer the icon belongs to
     * @param icon the icon that was loaded
     * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon()
     */
    void onCustomPrinterIconLoaded(in PrinterId printerId, in Icon icon);
}
