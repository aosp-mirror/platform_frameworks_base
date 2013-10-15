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
import android.provider.Settings;
import android.util.Log;

import java.util.HashMap;
import java.util.List;

/**
 * This class can be used to query the state of
 * NFC card emulation services.
 *
 * For a general introduction into NFC card emulation,
 * please read the <a href="{@docRoot}guide/topics/nfc/ce.html">
 * NFC card emulation developer guide</a>.</p>
 *
 * <p class="note">Use of this class requires the
 * {@link PackageManager#FEATURE_NFC_HOST_CARD_EMULATION} to be present
 * on the device.
 */
public final class CardEmulation {
    static final String TAG = "CardEmulation";

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
            "android.nfc.cardemulation.action.ACTION_CHANGE_DEFAULT";

    /**
     * The category extra for {@link #ACTION_CHANGE_DEFAULT}.
     *
     * @see #ACTION_CHANGE_DEFAULT
     */
    public static final String EXTRA_CATEGORY = "category";

    /**
     * The service {@link ComponentName} object passed in as an
     * extra for {@link #ACTION_CHANGE_DEFAULT}.
     *
     * @see #ACTION_CHANGE_DEFAULT
     */
    public static final String EXTRA_SERVICE_COMPONENT = "component";

    /**
     * Category used for NFC payment services.
     */
    public static final String CATEGORY_PAYMENT = "payment";

    /**
     * Category that can be used for all other card emulation
     * services.
     */
    public static final String CATEGORY_OTHER = "other";

    /**
     * Return value for {@link #getSelectionModeForCategory(String)}.
     *
     * <p>In this mode, the user has set a default service for this
     *    category.
     *
     * <p>When using ISO-DEP card emulation with {@link HostApduService}
     *    or {@link OffHostApduService}, if a remote NFC device selects
     *    any of the Application IDs (AIDs)
     *    that the default service has registered in this category,
     *    that service will automatically be bound to to handle
     *    the transaction.
     */
    public static final int SELECTION_MODE_PREFER_DEFAULT = 0;

    /**
     * Return value for {@link #getSelectionModeForCategory(String)}.
     *
     * <p>In this mode, when using ISO-DEP card emulation with {@link HostApduService}
     *    or {@link OffHostApduService}, whenever an Application ID (AID) of this category
     *    is selected, the user is asked which service he wants to use to handle
     *    the transaction, even if there is only one matching service.
     */
    public static final int SELECTION_MODE_ALWAYS_ASK = 1;

    /**
     * Return value for {@link #getSelectionModeForCategory(String)}.
     *
     * <p>In this mode, when using ISO-DEP card emulation with {@link HostApduService}
     *    or {@link OffHostApduService}, the user will only be asked to select a service
     *    if the Application ID (AID) selected by the reader has been registered by multiple
     *    services. If there is only one service that has registered for the AID,
     *    that service will be invoked directly.
     */
    public static final int SELECTION_MODE_ASK_IF_CONFLICT = 2;

    static boolean sIsInitialized = false;
    static HashMap<Context, CardEmulation> sCardEmus = new HashMap<Context, CardEmulation>();
    static INfcCardEmulation sService;

    final Context mContext;

    private CardEmulation(Context context, INfcCardEmulation service) {
        mContext = context.getApplicationContext();
        sService = service;
    }

    /**
     * Helper to get an instance of this class.
     *
     * @param adapter A reference to an NfcAdapter object.
     * @return
     */
    public static synchronized CardEmulation getInstance(NfcAdapter adapter) {
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
                if (!pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
                    Log.e(TAG, "This device does not support card emulation");
                    throw new UnsupportedOperationException();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "PackageManager query failed.");
                throw new UnsupportedOperationException();
            }
            sIsInitialized = true;
        }
        CardEmulation manager = sCardEmus.get(context);
        if (manager == null) {
            // Get card emu service
            INfcCardEmulation service = adapter.getCardEmulationService();
            manager = new CardEmulation(context, service);
            sCardEmus.put(context, manager);
        }
        return manager;
    }

    /**
     * Allows an application to query whether a service is currently
     * the default service to handle a card emulation category.
     *
     * <p>Note that if {@link #getSelectionModeForCategory(String)}
     * returns {@link #SELECTION_MODE_ALWAYS_ASK} or {@link #SELECTION_MODE_ASK_IF_CONFLICT},
     * this method will always return false. That is because in these
     * selection modes a default can't be set at the category level. For categories where
     * the selection mode is {@link #SELECTION_MODE_ALWAYS_ASK} or
     * {@link #SELECTION_MODE_ASK_IF_CONFLICT}, use
     * {@link #isDefaultServiceForAid(ComponentName, String)} to determine whether a service
     * is the default for a specific AID.
     *
     * @param service The ComponentName of the service
     * @param category The category
     * @return whether service is currently the default service for the category.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     */
    public boolean isDefaultServiceForCategory(ComponentName service, String category) {
        try {
            return sService.isDefaultServiceForCategory(UserHandle.myUserId(), service, category);
        } catch (RemoteException e) {
            // Try one more time
            recoverService();
            if (sService == null) {
                Log.e(TAG, "Failed to recover CardEmulationService.");
                return false;
            }
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
     * @return whether the service is the default handler for the specified AID
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
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
     * Returns the service selection mode for the passed in category.
     * Valid return values are:
     * <p>{@link #SELECTION_MODE_PREFER_DEFAULT} the user has requested a default
     *    service for this category, which will be preferred.
     * <p>{@link #SELECTION_MODE_ALWAYS_ASK} the user has requested to be asked
     *    every time what service he would like to use in this category.
     * <p>{@link #SELECTION_MODE_ASK_IF_CONFLICT} the user will only be asked
     *    to pick a service if there is a conflict.
     * @param category The category, for example {@link #CATEGORY_PAYMENT}
     * @return the selection mode for the passed in category
     */
    public int getSelectionModeForCategory(String category) {
        if (CATEGORY_PAYMENT.equals(category)) {
            String defaultComponent = Settings.Secure.getString(mContext.getContentResolver(),
                    Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT);
            if (defaultComponent != null) {
                return SELECTION_MODE_PREFER_DEFAULT;
            } else {
                return SELECTION_MODE_ALWAYS_ASK;
            }
        } else {
            // All other categories are in "only ask if conflict" mode
            return SELECTION_MODE_ASK_IF_CONFLICT;
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
