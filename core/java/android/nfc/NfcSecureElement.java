/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.nfc;

import android.nfc.tech.TagTechnology;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;

//import android.util.Log;

/**
 * This class provides the primary API for managing all aspects Secure Element.
 * Get an instance of this class by calling
 * Context.getSystemService(Context.NFC_SERVICE).
 * @hide
 */
public final class NfcSecureElement {

    private static final String TAG = "NfcSecureElement";

    private INfcSecureElement mService;


    /**
     * @hide
     */
    public NfcSecureElement(INfcSecureElement mSecureElementService) {
        mService = mSecureElementService;
    }

    public int openSecureElementConnection(String seType) throws IOException {
        if (seType.equals("SmartMX")) {
            try {
                int handle = mService.openSecureElementConnection();
                // Handle potential errors
                if (handle != 0) {
                    return handle;
                } else {
                    throw new IOException("SmartMX connection not allowed");
                }
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in openSecureElementConnection(): ", e);
                return 0;
            }

        } else if (seType.equals("UICC")) {
            return 0;
        } else {
        	throw new IOException("Wrong Secure Element type");
        }
    }


    public byte [] exchangeAPDU(int handle,byte [] data) throws IOException {


        // Perform exchange APDU
        try {
            byte[] response = mService.exchangeAPDU(handle, data);
            // Handle potential errors
            if (response == null) {
            	throw new IOException("Exchange APDU failed");
            }
            return response;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in exchangeAPDU(): ", e);
            return null;
        }
    }

    public void closeSecureElementConnection(int handle) throws IOException {

        try {
            int status = mService.closeSecureElementConnection(handle);
            // Handle potential errors
            if (ErrorCodes.isError(status)) {
            	throw new IOException("Error during the conection close");
            };
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in closeSecureElement(): ", e);
        }
    }


    /**
     * Returns target type. constants.
     *
     * @return Secure Element technology type. The possible values are defined in
     * {@link TagTechnology}
     *
     */
    public int[] getSecureElementTechList(int handle) throws IOException {
        try {
            return mService.getSecureElementTechList(handle);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getType(): ", e);
            return null;
        }
    }

    /**
     * Returns Secure Element UID.
     *
     * @return Secure Element UID.
     */
    public byte[] getSecureElementUid(int handle) throws IOException {

        byte[] uid = null;
        try {
            uid = mService.getSecureElementUid(handle);
            // Handle potential errors
            if (uid == null) {
                throw new IOException("Get Secure Element UID failed");
            }
            return uid;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getType(): ", e);
            return null;
        }
    }

}
