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
import android.os.SystemProperties;
import android.provider.Settings;


import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.Phone;

/**
 * Abstract class that represents the location of the device.  {@more}
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
        // TelephonyManager.getDefault().getCurrentPhoneType() handles the case when
        // ITelephony interface is not up yet.
        switch(TelephonyManager.getDefault().getCurrentPhoneType()) {
        case Phone.PHONE_TYPE_CDMA:
            return new CdmaCellLocation(bundle);
        case Phone.PHONE_TYPE_GSM:
            return new GsmCellLocation(bundle);
        default:
            return null;
        }
    }

    /**
     * @hide
     */
    public abstract void fillInNotifierBundle(Bundle bundle);

    /**
     * @hide
     */
    public abstract boolean isEmpty();

    /**
     * Return a new CellLocation object representing an unknown
     * location, or null for unknown/none phone radio types.
     *
     */
    public static CellLocation getEmpty() {
        // TelephonyManager.getDefault().getCurrentPhoneType() handles the case when
        // ITelephony interface is not up yet.
        switch(TelephonyManager.getDefault().getCurrentPhoneType()) {
        case Phone.PHONE_TYPE_CDMA:
            return new CdmaCellLocation();
        case Phone.PHONE_TYPE_GSM:
            return new GsmCellLocation();
        default:
            return null;
        }
    }
}
