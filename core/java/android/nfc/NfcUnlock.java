/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import java.util.HashMap;

/**
 * Provides an interface to read and update NFC unlock settings.
 * <p/>
 * Allows system services (currently exclusively LockSettingsService) to
 * register NFC tags to be used to unlock the device, as well as the ability
 * to enable/disable the service entirely.
 *
 */
public class NfcUnlock {

    /**
     * Action to unlock the device.
     *
     * @hide
     */
    public static final String ACTION_NFC_UNLOCK = "android.nfc.ACTION_NFC_UNLOCK";
    /**
     * Permission to unlock the device.
     *
     * @hide
     */
    public static final String NFC_UNLOCK_PERMISSION = "android.permission.NFC_UNLOCK";

    /**
     * Property to enable NFC Unlock
     *
     * @hide
     */
    public static final String PROPERTY = "ro.com.android.nfc.unlock";

    private static final String TAG = "NfcUnlock";
    private static HashMap<Context, NfcUnlock> sNfcUnlocks = new HashMap<Context, NfcUnlock>();

    private final Context mContext;
    private final boolean mEnabled;
    private INfcUnlockSettings sService;

    private NfcUnlock(Context context, INfcUnlockSettings service) {
        this.mContext = checkNotNull(context);
        this.sService = checkNotNull(service);
        this.mEnabled = getPropertyEnabled();
    }

    /**
     * Returns an instance of {@link NfcUnlock}.
     */
    public static synchronized NfcUnlock getInstance(NfcAdapter nfcAdapter) {
        Context context = nfcAdapter.getContext();
        if (context == null) {
            Log.e(TAG, "NfcAdapter context is null");
            throw new UnsupportedOperationException();
        }

        NfcUnlock manager = sNfcUnlocks.get(context);
        if (manager == null) {
            INfcUnlockSettings service = nfcAdapter.getNfcUnlockSettingsService();
            manager = new NfcUnlock(context, service);
            sNfcUnlocks.put(context, manager);
        }

        return manager;
    }

    /**
     * Registers the given {@code tag} as an unlock tag.
     *
     * @return true if the tag was successfully registered.
     * @hide
     */
    public boolean registerTag(Tag tag) {
        enforcePropertyEnabled();

        int currentUser = ActivityManager.getCurrentUser();

        try {
            return sService.registerTag(currentUser, tag);
        } catch (RemoteException e) {
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover NfcUnlockSettingsService");
                return false;
            }

            try {
                return sService.registerTag(currentUser, tag);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach NfcUnlockSettingsService", ee);
                return false;
            }
        }
    }

    /**
     * Deregisters the given {@code tag} as an unlock tag.
     *
     * @return true if the tag was successfully deregistered.
     * @hide
     */
    public boolean deregisterTag(long timestamp) {
        enforcePropertyEnabled();
        int currentUser = ActivityManager.getCurrentUser();

        try {
            return sService.deregisterTag(currentUser, timestamp);
        } catch (RemoteException e) {
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover NfcUnlockSettingsService");
                return false;
            }

            try {
                return sService.deregisterTag(currentUser, timestamp);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach NfcUnlockSettingsService", ee);
                return false;
            }
        }
    }

    /**
     * Determines the enable state of the NFC unlock feature.
     *
     * @return true if NFC unlock is enabled.
     */
    public boolean getNfcUnlockEnabled() {
        enforcePropertyEnabled();
        int currentUser = ActivityManager.getCurrentUser();

        try {
            return sService.getNfcUnlockEnabled(currentUser);
        } catch (RemoteException e) {
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover NfcUnlockSettingsService");
                return false;
            }

            try {
                return sService.getNfcUnlockEnabled(currentUser);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach NfcUnlockSettingsService", ee);
                return false;
            }
        }
    }

    /**
     * Set the enable state of the NFC unlock feature.
     *
     * @return true if the setting was successfully persisted.
     * @hide
     */
    public boolean setNfcUnlockEnabled(boolean enabled) {
        enforcePropertyEnabled();
        int currentUser = ActivityManager.getCurrentUser();

        try {
            sService.setNfcUnlockEnabled(currentUser, enabled);
            return true;
        }  catch (RemoteException e) {
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover NfcUnlockSettingsService");
                return false;
            }

            try {
                sService.setNfcUnlockEnabled(currentUser, enabled);
                return true;
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach NfcUnlockSettingsService", ee);
                return false;
            }

        }
    }

    /**
     * Returns a list of times (in millis since epoch) corresponding to when
     * unlock tags were registered.
     *
     * @hide
     */
    @Nullable
    public long[] getTagRegistryTimes() {
        enforcePropertyEnabled();
        int currentUser = ActivityManager.getCurrentUser();

        try {
            return sService.getTagRegistryTimes(currentUser);
        } catch (RemoteException e) {
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover NfcUnlockSettingsService");
                return null;
            }

            try {
                return sService.getTagRegistryTimes(currentUser);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach NfcUnlockSettingsService", ee);
                return null;
            }
        }
    }

    /**
     * @hide
     */
    public static boolean getPropertyEnabled() {
        return SystemProperties.get(PROPERTY).equals("ON");
    }

    private void recoverService() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        sService = adapter.getNfcUnlockSettingsService();
    }


    private void enforcePropertyEnabled() {
        if (!mEnabled) {
            throw new UnsupportedOperationException("NFC Unlock property is not enabled");
        }
    }
}
