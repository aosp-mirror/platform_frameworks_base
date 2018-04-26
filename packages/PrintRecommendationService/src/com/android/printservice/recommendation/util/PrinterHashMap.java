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

package com.android.printservice.recommendation.util;

import android.net.nsd.NsdServiceInfo;
import android.util.ArrayMap;

import java.net.InetAddress;
import java.util.ArrayList;

/**
 * Map to store {@link NsdServiceInfo} belonging to printers. If two infos have the same
 * {@link PrinterHashMap#getKey(NsdServiceInfo) key} they are considered the same.
 */
public class PrinterHashMap {
    private final ArrayMap<String, NsdServiceInfo> mPrinters = new ArrayMap<>();

    /**
     * Key uniquely identifying a printer.
     *
     * @param serviceInfo The service info describing the printer
     *
     * @return The key
     */
    public static String getKey(NsdServiceInfo serviceInfo) {
        return serviceInfo.getServiceName();
    }

    /**
     * Add a printer.
     *
     * @param device The service info of the printer
     *
     * @return The service info of the printer that was previously registered for the same key
     */
    public NsdServiceInfo addPrinter(NsdServiceInfo device) {
        return mPrinters.put(getKey(device), device);
    }

    /**
     * Remove a printer.
     *
     * @param device The service info of the printer
     *
     * @return The service info of the printer that was previously registered for the same key
     */
    public NsdServiceInfo removePrinter(NsdServiceInfo device) {
        return mPrinters.remove(getKey(device));
    }

    /**
     * @return the addresses of printers
     */
    public ArrayList<InetAddress> getPrinterAddresses() {
        int numPrinters = mPrinters.size();
        ArrayList<InetAddress> printerAddressess = new ArrayList<>(numPrinters);
        for (int i = 0; i < numPrinters; i++) {
            printerAddressess.add(mPrinters.valueAt(i).getHost());
        }

        return printerAddressess;
    }

    /**
     * Remove all printers
     */
    public void clear() {
        mPrinters.clear();
    }

    /**
     * @return {@code} true iff the map is empty
     */
    public boolean isEmpty() {
        return mPrinters.isEmpty();
    }
}
