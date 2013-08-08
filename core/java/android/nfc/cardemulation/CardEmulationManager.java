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

package android.nfc.cardemulation;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.ActivityThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.nfc.INfcCardEmulation;
import android.nfc.NfcAdapter;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import java.util.HashMap;
import java.util.List;

public final class CardEmulationManager {
    static final String TAG = "CardEmulationManager";

    /**
     * Activity action: ask the user to change the default
     * card emulation service for a certain category. This will
     * show a dialog that asks the user whether he wants to
     * replace the current default service with the service
     * identified with the ComponentName specified in
     * {@link #EXTRA_SERVICE_COMPONENT}, for the category
     * specified in {@link #EXTRA_CATEGORY}
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CHANGE_DEFAULT =
            "android.nfc.cardemulation.ACTION_CHANGE_DEFAULT";

    /**
     * The category extra for {@link #ACTION_CHANGE_DEFAULT}
     *
     * @see #ACTION_CHANGE_DEFAULT
     */
    public static final String EXTRA_CATEGORY = "category";

    /**
     * The ComponentName object passed in as a parcelable
     * extra for {@link #ACTION_CHANGE_DEFAULT}
     *
     * @see #ACTION_CHANGE_DEFAULT
     */
    public static final String EXTRA_SERVICE_COMPONENT = "component";

    /**
     * The payment category can be used to indicate that an AID
     * represents a payment application.
     */
    public static final String CATEGORY_PAYMENT = "payment";

    /**
     * If an AID group does not contain a category, or the
     * specified category is not defined by the platform version
     * that is parsing the AID group, all AIDs in the group will
     * automatically be categorized under the {@link #CATEGORY_OTHER}
     * category.
     */
    public static final String CATEGORY_OTHER = "other";

    static boolean sIsInitialized = false;
    static HashMap<Context, CardEmulationManager> sCardEmuManagers = new HashMap();
    static INfcCardEmulation sService;

    /**
     * @hide
     */
    public static final String PAYMENT_MODE_AUTO = "auto";

    /**
     * @hide
     */
    public static final String PAYMENT_MODE_MANUAL = "manual";

    final Context mContext;

    private CardEmulationManager(Context context, INfcCardEmulation service) {
        mContext = context.getApplicationContext();
        sService = service;
    }

    public static synchronized CardEmulationManager getInstance(NfcAdapter adapter) {
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
                if (!pm.hasSystemFeature(PackageManager.FEATURE_NFC_HCE)) {
                    Log.e(TAG, "This device does not support card emulation");
                    throw new UnsupportedOperationException();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "PackageManager query failed.");
                throw new UnsupportedOperationException();
            }
            sIsInitialized = true;
        }
        CardEmulationManager manager = sCardEmuManagers.get(context);
        if (manager == null) {
            // Get card emu service
            INfcCardEmulation service = adapter.getCardEmulationService();
            manager = new CardEmulationManager(context, service);
            sCardEmuManagers.put(context, manager);
        }
        return manager;
    }

    /**
     * Allows an application to query whether a service is currently
     * the default service to handle a card emulation category.
     *
     * @param service The ComponentName of the service
     * @param category The category
     * @return whether service is currently the default service for the category.
     */
    public boolean isDefaultServiceForCategory(ComponentName service, String category) {
        try {
            return sService.isDefaultServiceForCategory(UserHandle.myUserId(), service, category);
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            try {
                return sService.isDefaultServiceForCategory(UserHandle.myUserId(), service,
                        category);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return false;
            }
        }
    }

    /**
     *
     * Allows an application to query whether a service is currently
     * the default handler for a specified ISO7816-4 Application ID.
     *
     * @param service The ComponentName of the service
     * @param aid The ISO7816-4 Application ID
     * @return
     */
    public boolean isDefaultServiceForAid(ComponentName service, String aid) {
        try {
            return sService.isDefaultServiceForAid(UserHandle.myUserId(), service, aid);
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return false;
            }
            try {
                return sService.isDefaultServiceForAid(UserHandle.myUserId(), service, aid);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach CardEmulationService.");
                return false;
            }
        }
    }

    /**
     * @hide
     */
    public boolean setDefaultServiceForCategory(ComponentName service, String category) {
        try {
            return sService.setDefaultServiceForCategory(UserHandle.myUserId(), service, category);
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return false;
            }
            try {
                return sService.setDefaultServiceForCategory(UserHandle.myUserId(), service,
                        category);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach CardEmulationService.");
                return false;
            }
        }
    }

    /**
     * @hide
     */
    public boolean setDefaultForNextTap(ComponentName service) {
        try {
            return sService.setDefaultForNextTap(UserHandle.myUserId(), service);
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return false;
            }
            try {
                return sService.setDefaultForNextTap(UserHandle.myUserId(), service);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach CardEmulationService.");
                return false;
            }
        }
    }
    /**
     * @hide
     */
    public List<ApduServiceInfo> getServices(String category) {
        try {
            return sService.getServices(UserHandle.myUserId(), category);
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return null;
            }
            try {
                return sService.getServices(UserHandle.myUserId(), category);
            } catch (RemoteException ee) {
                Log.e(TAG, "Failed to reach CardEmulationService.");
                return null;
            }
        }
    }

    void recoverService() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        sService = adapter.getCardEmulationService();
    }
}
