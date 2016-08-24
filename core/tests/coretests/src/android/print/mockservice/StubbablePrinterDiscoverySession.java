/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.print.mockservice;

import android.os.CancellationSignal;
import android.print.PrinterId;
import android.printservice.CustomPrinterIconCallback;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;

import java.util.List;

public class StubbablePrinterDiscoverySession extends PrinterDiscoverySession {
    private final PrintService mService;
    private final PrinterDiscoverySessionCallbacks mCallbacks;

    public StubbablePrinterDiscoverySession(PrintService service,
            PrinterDiscoverySessionCallbacks callbacks) {
        mService = service;
        mCallbacks = callbacks;
        if (mCallbacks != null) {
            mCallbacks.setSession(this);
        }
    }

    public PrintService getService() {
        return mService;
    }

    @Override
    public void onStartPrinterDiscovery(List<PrinterId> priorityList) {
        if (mCallbacks != null) {
            mCallbacks.onStartPrinterDiscovery(priorityList);
        }
    }

    @Override
    public void onStopPrinterDiscovery() {
        if (mCallbacks != null) {
            mCallbacks.onStopPrinterDiscovery();
        }
    }

    @Override
    public void onValidatePrinters(List<PrinterId> printerIds) {
        if (mCallbacks != null) {
            mCallbacks.onValidatePrinters(printerIds);
        }
    }

    @Override
    public void onStartPrinterStateTracking(PrinterId printerId) {
        if (mCallbacks != null) {
            mCallbacks.onStartPrinterStateTracking(printerId);
        }
    }

    @Override
    public void onRequestCustomPrinterIcon(PrinterId printerId,
            CancellationSignal cancellationSignal, CustomPrinterIconCallback callback) {
        if (mCallbacks != null) {
            mCallbacks.onRequestCustomPrinterIcon(printerId, cancellationSignal, callback);
        }
    }

    @Override
    public void onStopPrinterStateTracking(PrinterId printerId) {
        if (mCallbacks != null) {
            mCallbacks.onStopPrinterStateTracking(printerId);
        }
    }

    @Override
    public void onDestroy() {
        if (mCallbacks != null) {
            mCallbacks.onDestroy();
        }
    }
}
