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

import android.graphics.drawable.Icon;
import android.print.PrintJobInfo;
import android.print.PrinterId;
import java.util.List;

/**
 * Callbacks for communication with the print spooler service.
 *
 * @see android.print.IPrintSpoolerService
 *
 * @hide
 */
oneway interface IPrintSpoolerCallbacks {
    void onGetPrintJobInfosResult(in List<PrintJobInfo> printJob, int sequence);
    void onCancelPrintJobResult(boolean canceled, int sequence);
    void onSetPrintJobStateResult(boolean success, int sequence);
    void onSetPrintJobTagResult(boolean success, int sequence);
    void onGetPrintJobInfoResult(in PrintJobInfo printJob, int sequence);

    /**
     * Deliver the result of a request of a custom printer icon.
     *
     * @param icon the icon that was retrieved, or null if no icon could be
     *             found
     * @param sequence the sequence number of the call to get the icon
     */
    void onGetCustomPrinterIconResult(in Icon icon, int sequence);

    /**
     * Declare that the print spooler cached a custom printer icon.
     *
     * @param sequence the sequence number of the call to cache the icon
     */
    void onCustomPrinterIconCached(int sequence);

    /**
     * Declare that the custom printer icon cache was cleared.
     *
     * @param sequence the sequence number of the call to clear the cache
     */
    void customPrinterIconCacheCleared(int sequence);
}
