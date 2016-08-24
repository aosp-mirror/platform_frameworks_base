/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.drawable.Icon;
import android.os.RemoteException;
import android.print.PrinterId;
import android.util.Log;


/**
 * Callback for {@link PrinterDiscoverySession#onRequestCustomPrinterIcon}.
 */
public final class CustomPrinterIconCallback {
    /** The printer the call back is for */
    private final @NonNull PrinterId mPrinterId;
    private final @NonNull IPrintServiceClient mObserver;
    private static final String LOG_TAG = "CustomPrinterIconCB";

    /**
     * Create a callback class to be used once a icon is loaded
     *
     * @param printerId The printer the icon should be loaded for
     * @param observer The observer that needs to be notified about the update.
     */
    CustomPrinterIconCallback(@NonNull PrinterId printerId, @NonNull IPrintServiceClient observer) {
        mPrinterId = printerId;
        mObserver = observer;
    }

    /**
     * Provide a new icon for a printer. Can be called more than once to update the icon.
     *
     * @param icon The new icon for the printer or null to unset the current icon
     * @return true iff the icon could be updated
     */
    public boolean onCustomPrinterIconLoaded(@Nullable Icon icon) {
        try {
            mObserver.onCustomPrinterIconLoaded(mPrinterId, icon);
        } catch (RemoteException e) {
            Log.e(LOG_TAG , "Could not update icon", e);
            return false;
        }

        return true;
    }
}
