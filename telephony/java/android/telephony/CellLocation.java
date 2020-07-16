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

import android.app.ActivityThread;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;

import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.PhoneConstants;

/**
 * Abstract class that represents the location of the device.  {@more}
 */
public abstract class CellLocation {

    /**
     * This method will not do anything.
     *
     * Whenever location changes, a callback will automatically be be sent to
     * all registrants of {@link PhoneStateListener#LISTEN_CELL_LOCATION}.
     *
     * <p>This method is a no-op for callers targeting SDK level 31 or greater.
     * <p>This method is a no-op for callers that target SDK level 29 or 30 and lack
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION}.
     * <p>This method is a no-op for callers that target SDK level 28 or below and lack
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION}.
     *
     * Callers wishing to request a single location update should use
     * {@link TelephonyManager#requestCellInfoUpdate}.
     *
     * @deprecated this method has undesirable side-effects, and it calls into the OS without
     * access to a {@link android.content.Context Context}, meaning that certain safety checks and
     * attribution are error-prone. Given that this method has numerous downsides, and given that
     * there are long-available superior alternatives, callers are strongly discouraged from using
     * this method.
     */
    @Deprecated
    public static void requestLocationUpdate() {
        // Since this object doesn't have a context, this is the best we can do.
        final Context appContext = ActivityThread.currentApplication();
        if (appContext == null) return; // should never happen

        try {
            ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.getService("phone"));
            if (phone != null) {
                phone.updateServiceLocationWithPackageName(appContext.getOpPackageName());
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
    @UnsupportedAppUsage
    public static CellLocation newFromBundle(Bundle bundle) {
        // TelephonyManager.getDefault().getCurrentPhoneType() handles the case when
        // ITelephony interface is not up yet.
        switch(TelephonyManager.getDefault().getCurrentPhoneType()) {
        case PhoneConstants.PHONE_TYPE_CDMA:
            return new CdmaCellLocation(bundle);
        case PhoneConstants.PHONE_TYPE_GSM:
            return new GsmCellLocation(bundle);
        default:
            return null;
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public abstract void fillInNotifierBundle(Bundle bundle);

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public abstract boolean isEmpty();

    /**
     * Invalidate this object.  The location area code and the cell id are set to -1.
     * @hide
     */
    public abstract void setStateInvalid();

    /**
     * Return a new CellLocation object representing an unknown
     * location, or null for unknown/none phone radio types.
     *
     */
    public static CellLocation getEmpty() {
        // TelephonyManager.getDefault().getCurrentPhoneType() handles the case when
        // ITelephony interface is not up yet.
        switch(TelephonyManager.getDefault().getCurrentPhoneType()) {
        case PhoneConstants.PHONE_TYPE_CDMA:
            return new CdmaCellLocation();
        case PhoneConstants.PHONE_TYPE_GSM:
            return new GsmCellLocation();
        default:
            return null;
        }
    }
}
