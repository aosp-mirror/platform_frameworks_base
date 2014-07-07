/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.phone;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.telecomm.ITelecommService;

/**
 * Exposes call-related functionality for use by phone and dialer apps.
 */
public final class PhoneManager {
    private static final String TAG = PhoneManager.class.getSimpleName();

    private final Context mContext;
    private final ITelecommService mService;

    /**
     * @hide
     */
    public PhoneManager(Context context, ITelecommService service) {
        Context appContext = context.getApplicationContext();
        if (appContext != null) {
            mContext = appContext;
        } else {
            mContext = context;
        }

        mService = service;
    }

    /**
     * Processes the specified dial string as an MMI code.
     * <p>
     * Requires that the method-caller be set as the system dialer app.
     * </p>
     *
     * @param dialString The digits to dial.
     * @return True if the digits were processed as an MMI code, false otherwise.
     */
    public boolean handlePinMmi(String dialString) {
        try {
            return mService.handlePinMmi(dialString);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecommService#handlePinMmi", e);
        }
        return false;
    }

    /**
     * Removes the missed-call notification if one is present.
     * <p>
     * Requires that the method-caller be set as the system dialer app.
     * </p>
     */
    public void cancelMissedCallsNotification() {
        try {
            mService.cancelMissedCallsNotification();
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecommService#cancelMissedCallNotification", e);
        }
    }

    /**
     * Brings the in-call screen to the foreground if there is an ongoing call. If there is
     * currently no ongoing call, then this method does nothing.
     * <p>
     * Requires that the method-caller be set as the system dialer app or have the
     * {@link android.Manifest.permission#READ_PHONE_STATE} permission.
     * </p>
     *
     * @param showDialpad Brings up the in-call dialpad as part of showing the in-call screen.
     */
    public void showCallScreen(boolean showDialpad) {
        try {
            mService.showCallScreen(showDialpad);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling ITelecommService#showCallScreen", e);
        }
    }

    /**
     * Returns whether there is an ongoing phone call.
     * <p>
     * Requires permission: {@link android.Manifest.permission#READ_PHONE_STATE}
     * </p>
     */
    public boolean isInAPhoneCall() {
        try {
            return mService.isInAPhoneCall();
        } catch (RemoteException e) {
            Log.e(TAG, "Error caling ITelecommService#isInAPhoneCall", e);
        }
        return false;
    }
}
