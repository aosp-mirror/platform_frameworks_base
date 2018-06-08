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

package android.nfc.cardemulation;

import android.app.Activity;
import android.app.ActivityThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.nfc.INfcFCardEmulation;
import android.nfc.NfcAdapter;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import java.util.HashMap;
import java.util.List;

/**
 * This class can be used to query the state of
 * NFC-F card emulation services.
 *
 * For a general introduction into NFC card emulation,
 * please read the <a href="{@docRoot}guide/topics/connectivity/nfc/hce.html">
 * NFC card emulation developer guide</a>.</p>
 *
 * <p class="note">Use of this class requires the
 * {@link PackageManager#FEATURE_NFC_HOST_CARD_EMULATION_NFCF}
 * to be present on the device.
 */
public final class NfcFCardEmulation {
    static final String TAG = "NfcFCardEmulation";

    static boolean sIsInitialized = false;
    static HashMap<Context, NfcFCardEmulation> sCardEmus = new HashMap<Context, NfcFCardEmulation>();
    static INfcFCardEmulation sService;

    final Context mContext;

    private NfcFCardEmulation(Context context, INfcFCardEmulation service) {
        mContext = context.getApplicationContext();
        sService = service;
    }

    /**
     * Helper to get an instance of this class.
     *
     * @param adapter A reference to an NfcAdapter object.
     * @return
     */
    public static synchronized NfcFCardEmulation getInstance(NfcAdapter adapter) {
        if (adapter == null) throw new NullPointerException("NfcAdapter is null");
        Context context = adapter.getContext();
        if (context == null) {
            Log.e(TAG, "NfcAdapter context is null.");
            throw new UnsupportedOperationException();
        }
        if (!sIsInitialized) {
            IPackageManager pm = ActivityThread.getPackageManager();
            if (pm == null) {
                Log.e(TAG, "Cannot get PackageManager");
                throw new UnsupportedOperationException();
            }
            try {
                if (!pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF, 0)) {
                    Log.e(TAG, "This device does not support NFC-F card emulation");
                    throw new UnsupportedOperationException();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "PackageManager query failed.");
                throw new UnsupportedOperationException();
            }
            sIsInitialized = true;
        }
        NfcFCardEmulation manager = sCardEmus.get(context);
        if (manager == null) {
            // Get card emu service
            INfcFCardEmulation service = adapter.getNfcFCardEmulationService();
            if (service == null) {
                Log.e(TAG, "This device does not implement the INfcFCardEmulation interface.");
                throw new UnsupportedOperationException();
            }
            manager = new NfcFCardEmulation(context, service);
            sCardEmus.put(context, manager);
        }
        return manager;
    }

    /**
     * Retrieves the current System Code for the specified service.
     *
     * <p>Before calling {@link #registerSystemCodeForService(ComponentName, String)},
     * the System Code contained in the Manifest file is returned. After calling
     * {@link #registerSystemCodeForService(ComponentName, String)}, the System Code
     * registered there is returned. After calling
     * {@link #unregisterSystemCodeForService(ComponentName)}, "null" is returned.
     *
     * @param service The component name of the service
     * @return the current System Code
     */
    public String getSystemCodeForService(ComponentName service) throws RuntimeException {
        if (service == null) {
            throw new NullPointerException("service is null");
        }
        try {
            return sService.getSystemCodeForService(mContext.getUserId(), service);
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return null;
            }
            try {
                return sService.getSystemCodeForService(mContext.getUserId(), service);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach CardEmulationService.");
                ee.rethrowAsRuntimeException();
                return null;
            }
        }
    }

    /**
     * Registers a System Code for the specified service.
     *
     * <p>The System Code must be in range from "4000" to "4FFF" (excluding "4*FF").
     *
     * <p>If a System Code was previously registered for this service
     * (either statically through the manifest, or dynamically by using this API),
     * it will be replaced with this one.
     *
     * <p>Even if the same System Code is already registered for another service,
     * this method succeeds in registering the System Code.
     *
     * <p>Note that you can only register a System Code for a service that
     * is running under the same UID as the caller of this API. Typically
     * this means you need to call this from the same
     * package as the service itself, though UIDs can also
     * be shared between packages using shared UIDs.
     *
     * @param service The component name of the service
     * @param systemCode The System Code to be registered
     * @return whether the registration was successful.
     */
    public boolean registerSystemCodeForService(ComponentName service, String systemCode)
            throws RuntimeException {
        if (service == null || systemCode == null) {
            throw new NullPointerException("service or systemCode is null");
        }
        try {
            return sService.registerSystemCodeForService(mContext.getUserId(),
                    service, systemCode);
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return false;
            }
            try {
                return sService.registerSystemCodeForService(mContext.getUserId(),
                        service, systemCode);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach CardEmulationService.");
                ee.rethrowAsRuntimeException();
                return false;
            }
        }
    }

    /**
     * Removes a registered System Code for the specified service.
     *
     * @param service The component name of the service
     * @return whether the System Code was successfully removed.
     */
    public boolean unregisterSystemCodeForService(ComponentName service) throws RuntimeException {
        if (service == null) {
            throw new NullPointerException("service is null");
        }
        try {
            return sService.removeSystemCodeForService(mContext.getUserId(), service);
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return false;
            }
            try {
                return sService.removeSystemCodeForService(mContext.getUserId(), service);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach CardEmulationService.");
                ee.rethrowAsRuntimeException();
                return false;
            }
        }
    }

    /**
     * Retrieves the current NFCID2 for the specified service.
     *
     * <p>Before calling {@link #setNfcid2ForService(ComponentName, String)},
     * the NFCID2 contained in the Manifest file is returned. If "random" is specified
     * in the Manifest file, a random number assigned by the system at installation time
     * is returned. After setting an NFCID2
     * with {@link #setNfcid2ForService(ComponentName, String)}, this NFCID2 is returned.
     *
     * @param service The component name of the service
     * @return the current NFCID2
     */
    public String getNfcid2ForService(ComponentName service) throws RuntimeException {
        if (service == null) {
            throw new NullPointerException("service is null");
        }
        try {
            return sService.getNfcid2ForService(mContext.getUserId(), service);
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return null;
            }
            try {
                return sService.getNfcid2ForService(mContext.getUserId(), service);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach CardEmulationService.");
                ee.rethrowAsRuntimeException();
                return null;
            }
        }
    }

    /**
     * Set a NFCID2 for the specified service.
     *
     * <p>The NFCID2 must be in range from "02FE000000000000" to "02FEFFFFFFFFFFFF".
     *
     * <p>If a NFCID2 was previously set for this service
     * (either statically through the manifest, or dynamically by using this API),
     * it will be replaced.
     *
     * <p>Note that you can only set the NFCID2 for a service that
     * is running under the same UID as the caller of this API. Typically
     * this means you need to call this from the same
     * package as the service itself, though UIDs can also
     * be shared between packages using shared UIDs.
     *
     * @param service The component name of the service
     * @param nfcid2 The NFCID2 to be registered
     * @return whether the setting was successful.
     */
    public boolean setNfcid2ForService(ComponentName service, String nfcid2)
            throws RuntimeException {
        if (service == null || nfcid2 == null) {
            throw new NullPointerException("service or nfcid2 is null");
        }
        try {
            return sService.setNfcid2ForService(mContext.getUserId(),
                    service, nfcid2);
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return false;
            }
            try {
                return sService.setNfcid2ForService(mContext.getUserId(),
                        service, nfcid2);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach CardEmulationService.");
                ee.rethrowAsRuntimeException();
                return false;
            }
        }
    }

    /**
     * Allows a foreground application to specify which card emulation service
     * should be enabled while a specific Activity is in the foreground.
     *
     * <p>The specified HCE-F service is only enabled when the corresponding application is
     * in the foreground and this method has been called. When the application is moved to
     * the background, {@link #disableService(Activity)} is called, or
     * NFCID2 or System Code is replaced, the HCE-F service is disabled.
     *
     * <p>The specified Activity must currently be in resumed state. A good
     * paradigm is to call this method in your {@link Activity#onResume}, and to call
     * {@link #disableService(Activity)} in your {@link Activity#onPause}.
     *
     * <p>Note that this preference is not persisted by the OS, and hence must be
     * called every time the Activity is resumed.
     *
     * @param activity The activity which prefers this service to be invoked
     * @param service The service to be preferred while this activity is in the foreground
     * @return whether the registration was successful
     */
    public boolean enableService(Activity activity, ComponentName service) throws RuntimeException {
        if (activity == null || service == null) {
            throw new NullPointerException("activity or service is null");
        }
        // Verify the activity is in the foreground before calling into NfcService
        if (!activity.isResumed()) {
            throw new IllegalArgumentException("Activity must be resumed.");
        }
        try {
            return sService.enableNfcFForegroundService(service);
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return false;
            }
            try {
                return sService.enableNfcFForegroundService(service);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach CardEmulationService.");
                ee.rethrowAsRuntimeException();
                return false;
            }
        }
    }

    /**
     * Disables the service for the specified Activity.
     *
     * <p>Note that the specified Activity must still be in resumed
     * state at the time of this call. A good place to call this method
     * is in your {@link Activity#onPause} implementation.
     *
     * @param activity The activity which the service was registered for
     * @return true when successful
     */
    public boolean disableService(Activity activity) throws RuntimeException {
        if (activity == null) {
            throw new NullPointerException("activity is null");
        }
        if (!activity.isResumed()) {
            throw new IllegalArgumentException("Activity must be resumed.");
        }
        try {
            return sService.disableNfcFForegroundService();
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return false;
            }
            try {
                return sService.disableNfcFForegroundService();
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach CardEmulationService.");
                ee.rethrowAsRuntimeException();
                return false;
            }
        }
    }

    /**
     * @hide
     */
    public List<NfcFServiceInfo> getNfcFServices() {
        try {
            return sService.getNfcFServices(mContext.getUserId());
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return null;
            }
            try {
                return sService.getNfcFServices(mContext.getUserId());
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach CardEmulationService.");
                return null;
            }
        }
    }

    /**
     * @hide
     */
    public int getMaxNumOfRegisterableSystemCodes() {
        try {
            return sService.getMaxNumOfRegisterableSystemCodes();
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return -1;
            }
            try {
                return sService.getMaxNumOfRegisterableSystemCodes();
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach CardEmulationService.");
                return -1;
            }
        }
    }

    /**
     * @hide
     */
    public static boolean isValidSystemCode(String systemCode) {
        if (systemCode == null) {
            return false;
        }
        if (systemCode.length() != 4) {
            Log.e(TAG, "System Code " + systemCode + " is not a valid System Code.");
            return false;
        }
        // check if the value is between "4000" and "4FFF" (excluding "4*FF")
        if (!systemCode.startsWith("4") || systemCode.toUpperCase().endsWith("FF")) {
            Log.e(TAG, "System Code " + systemCode + " is not a valid System Code.");
            return false;
        }
        try {
            Integer.parseInt(systemCode, 16);
        } catch (NumberFormatException e) {
            Log.e(TAG, "System Code " + systemCode + " is not a valid System Code.");
            return false;
        }
        return true;
    }

    /**
     * @hide
     */
    public static boolean isValidNfcid2(String nfcid2) {
        if (nfcid2 == null) {
            return false;
        }
        if (nfcid2.length() != 16) {
            Log.e(TAG, "NFCID2 " + nfcid2 + " is not a valid NFCID2.");
            return false;
        }
        // check if the the value starts with "02FE"
        if (!nfcid2.toUpperCase().startsWith("02FE")) {
            Log.e(TAG, "NFCID2 " + nfcid2 + " is not a valid NFCID2.");
            return false;
        }
        try {
            Long.parseLong(nfcid2, 16);
        } catch (NumberFormatException e) {
            Log.e(TAG, "NFCID2 " + nfcid2 + " is not a valid NFCID2.");
            return false;
        }
        return true;
    }

    void recoverService() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        sService = adapter.getNfcFCardEmulationService();
    }

}

