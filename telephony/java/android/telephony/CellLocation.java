/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.telephony;

import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.gsm.GsmCellLocation;
import com.android.internal.telephony.ITelephony;

/**
 * Abstract class that represents the location of the device.  Currently the only
 * subclass is {@link android.telephony.gsm.GsmCellLocation}.  {@more}
 */
public abstract class CellLocation {

    /**
     * Request an update of the current location.  If the location has changed,
     * a broadcast will be sent to everyone registered with {@link
     * PhoneStateListener#LISTEN_CELL_LOCATION}.
     */
    public static void requestLocationUpdate() {
        try {
            ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.getService("phone"));
            if (phone != null) {
                phone.updateServiceLocation();
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Create a new CellLocation from a intent notifier Bundle
     *
     * This method is used by PhoneStateIntentReceiver and maybe by
     * external applications.
     *
     * @param bundle Bundle from intent notifier
     * @return newly created CellLocation
     *
     * @hide
     */
    public static CellLocation newFromBundle(Bundle bundle) {
        return new GsmCellLocation(bundle);
    }

    /**
     * @hide
     */
    public abstract void fillInNotifierBundle(Bundle bundle);

    /**
     * Return a new CellLocation object representing an unknown location.
     */
    public static CellLocation getEmpty() {
        return new GsmCellLocation();
    }
}
