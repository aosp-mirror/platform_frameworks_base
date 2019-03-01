/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed urnder the Apache License, Version 2.0 (the "License");
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
package android.net;

import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

/**
 * A class allowing apps handling the {@link ConnectivityManager#ACTION_CAPTIVE_PORTAL_SIGN_IN}
 * activity to indicate to the system different outcomes of captive portal sign in.  This class is
 * passed as an extra named {@link ConnectivityManager#EXTRA_CAPTIVE_PORTAL} with the
 * {@code ACTION_CAPTIVE_PORTAL_SIGN_IN} activity.
 */
public class CaptivePortal implements Parcelable {
    /** @hide */
    @SystemApi
    @TestApi
    public static final int APP_RETURN_DISMISSED    = 0;
    /** @hide */
    @SystemApi
    @TestApi
    public static final int APP_RETURN_UNWANTED     = 1;
    /** @hide */
    @SystemApi
    @TestApi
    public static final int APP_RETURN_WANTED_AS_IS = 2;

    private final IBinder mBinder;

    /** @hide */
    @SystemApi
    @TestApi
    public CaptivePortal(IBinder binder) {
        mBinder = binder;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeStrongBinder(mBinder);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<CaptivePortal> CREATOR
            = new Parcelable.Creator<CaptivePortal>() {
        @Override
        public CaptivePortal createFromParcel(Parcel in) {
            return new CaptivePortal(in.readStrongBinder());
        }

        @Override
        public CaptivePortal[] newArray(int size) {
            return new CaptivePortal[size];
        }
    };

    /**
     * Indicate to the system that the captive portal has been
     * dismissed.  In response the framework will re-evaluate the network's
     * connectivity and might take further action thereafter.
     */
    public void reportCaptivePortalDismissed() {
        try {
            ICaptivePortal.Stub.asInterface(mBinder).appResponse(APP_RETURN_DISMISSED);
        } catch (RemoteException e) {
        }
    }

    /**
     * Indicate to the system that the user does not want to pursue signing in to the
     * captive portal and the system should continue to prefer other networks
     * without captive portals for use as the default active data network.  The
     * system will not retest the network for a captive portal so as to avoid
     * disturbing the user with further sign in to network notifications.
     */
    public void ignoreNetwork() {
        try {
            ICaptivePortal.Stub.asInterface(mBinder).appResponse(APP_RETURN_UNWANTED);
        } catch (RemoteException e) {
        }
    }

    /**
     * Indicate to the system the user wants to use this network as is, even though
     * the captive portal is still in place.  The system will treat the network
     * as if it did not have a captive portal when selecting the network to use
     * as the default active data network. This may result in this network
     * becoming the default active data network, which could disrupt network
     * connectivity for apps because the captive portal is still in place.
     * @hide
     */
    @SystemApi
    @TestApi
    public void useNetwork() {
        try {
            ICaptivePortal.Stub.asInterface(mBinder).appResponse(APP_RETURN_WANTED_AS_IS);
        } catch (RemoteException e) {
        }
    }

    /**
     * Log a captive portal login event.
     * @hide
     */
    @SystemApi
    @TestApi
    public void logEvent(int eventId, String packageName) {
        try {
            ICaptivePortal.Stub.asInterface(mBinder).logEvent(eventId, packageName);
        } catch (RemoteException e) {
        }
    }
}
