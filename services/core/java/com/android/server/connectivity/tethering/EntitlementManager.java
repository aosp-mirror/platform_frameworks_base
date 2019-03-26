/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.connectivity.tethering;

import static android.net.ConnectivityManager.EXTRA_ADD_TETHER_TYPE;
import static android.net.ConnectivityManager.EXTRA_PROVISION_CALLBACK;
import static android.net.ConnectivityManager.EXTRA_REM_TETHER_TYPE;
import static android.net.ConnectivityManager.EXTRA_RUN_PROVISION;
import static android.net.ConnectivityManager.EXTRA_SET_ALARM;
import static android.net.ConnectivityManager.TETHER_ERROR_ENTITLEMENT_UNKONWN;
import static android.net.ConnectivityManager.TETHER_ERROR_NO_ERROR;
import static android.net.ConnectivityManager.TETHER_ERROR_PROVISION_FAILED;

import static com.android.internal.R.string.config_wifi_tether_enable;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.util.SharedLog;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.StateMachine;
import com.android.server.connectivity.MockableSystemProperties;

/**
 * This class encapsulates entitlement/provisioning mechanics
 * provisioning check only applies to the use of the mobile network as an upstream
 *
 * @hide
 */
public class EntitlementManager {
    private static final String TAG = EntitlementManager.class.getSimpleName();
    private static final boolean DBG = false;

    // {@link ComponentName} of the Service used to run tether provisioning.
    private static final ComponentName TETHER_SERVICE = ComponentName.unflattenFromString(
            Resources.getSystem().getString(config_wifi_tether_enable));
    protected static final String DISABLE_PROVISIONING_SYSPROP_KEY = "net.tethering.noprovisioning";

    // The ArraySet contains enabled downstream types, ex:
    // {@link ConnectivityManager.TETHERING_WIFI}
    // {@link ConnectivityManager.TETHERING_USB}
    // {@link ConnectivityManager.TETHERING_BLUETOOTH}
    @GuardedBy("mCurrentTethers")
    private final ArraySet<Integer> mCurrentTethers;
    private final Context mContext;
    private final MockableSystemProperties mSystemProperties;
    private final SharedLog mLog;
    private final Handler mMasterHandler;
    private final SparseIntArray mEntitlementCacheValue;
    @Nullable
    private TetheringConfiguration mConfig;

    public EntitlementManager(Context ctx, StateMachine tetherMasterSM, SharedLog log,
            MockableSystemProperties systemProperties) {
        mContext = ctx;
        mLog = log.forSubComponent(TAG);
        mCurrentTethers = new ArraySet<Integer>();
        mSystemProperties = systemProperties;
        mEntitlementCacheValue = new SparseIntArray();
        mMasterHandler = tetherMasterSM.getHandler();
    }

    /**
     * Pass a new TetheringConfiguration instance each time when
     * Tethering#updateConfiguration() is called.
     */
    public void updateConfiguration(TetheringConfiguration conf) {
        mConfig = conf;
    }

    /**
     * Tell EntitlementManager that a given type of tethering has been enabled
     *
     * @param type Tethering type
     */
    public void startTethering(int type) {
        synchronized (mCurrentTethers) {
            mCurrentTethers.add(type);
        }
    }

    /**
     * Tell EntitlementManager that a given type of tethering has been disabled
     *
     * @param type Tethering type
     */
    public void stopTethering(int type) {
        synchronized (mCurrentTethers) {
            mCurrentTethers.remove(type);
        }
    }

    /**
     * Check if the device requires a provisioning check in order to enable tethering.
     *
     * @return a boolean - {@code true} indicating tether provisioning is required by the carrier.
     */
    @VisibleForTesting
    public boolean isTetherProvisioningRequired() {
        if (mSystemProperties.getBoolean(DISABLE_PROVISIONING_SYSPROP_KEY, false)
                || mConfig.provisioningApp.length == 0) {
            return false;
        }
        if (carrierConfigAffirmsEntitlementCheckNotRequired()) {
            return false;
        }
        return (mConfig.provisioningApp.length == 2);
    }

    /**
     * Re-check tethering provisioning for enabled downstream tether types.
     * Reference ConnectivityManager.TETHERING_{@code *} for each tether type.
     */
    public void reevaluateSimCardProvisioning() {
        synchronized (mEntitlementCacheValue) {
            mEntitlementCacheValue.clear();
        }

        if (!mConfig.hasMobileHotspotProvisionApp()) return;
        if (carrierConfigAffirmsEntitlementCheckNotRequired()) return;

        final ArraySet<Integer> reevaluateType;
        synchronized (mCurrentTethers) {
            reevaluateType = new ArraySet<Integer>(mCurrentTethers);
        }
        for (Integer type : reevaluateType) {
            startProvisionIntent(type);
        }
    }

    /** Get carrier configuration bundle. */
    public PersistableBundle getCarrierConfig() {
        final CarrierConfigManager configManager = (CarrierConfigManager) mContext
                .getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager == null) return null;

        final PersistableBundle carrierConfig = configManager.getConfig();

        if (CarrierConfigManager.isConfigForIdentifiedCarrier(carrierConfig)) {
            return carrierConfig;
        }

        return null;
    }

    // The logic here is aimed solely at confirming that a CarrierConfig exists
    // and affirms that entitlement checks are not required.
    //
    // TODO: find a better way to express this, or alter the checking process
    // entirely so that this is more intuitive.
    private boolean carrierConfigAffirmsEntitlementCheckNotRequired() {
        // Check carrier config for entitlement checks
        final PersistableBundle carrierConfig = getCarrierConfig();
        if (carrierConfig == null) return false;

        // A CarrierConfigManager was found and it has a config.
        final boolean isEntitlementCheckRequired = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_REQUIRE_ENTITLEMENT_CHECKS_BOOL);
        return !isEntitlementCheckRequired;
    }

    public void runSilentTetherProvisioningAndEnable(int type, ResultReceiver receiver) {
        Intent intent = new Intent();
        intent.putExtra(EXTRA_ADD_TETHER_TYPE, type);
        intent.putExtra(EXTRA_RUN_PROVISION, true);
        intent.putExtra(EXTRA_PROVISION_CALLBACK, receiver);
        intent.setComponent(TETHER_SERVICE);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.startServiceAsUser(intent, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void runUiTetherProvisioningAndEnable(int type, ResultReceiver receiver) {
        runUiTetherProvisioning(type, receiver);
    }

    @VisibleForTesting
    protected void runUiTetherProvisioning(int type, ResultReceiver receiver) {
        Intent intent = new Intent(Settings.ACTION_TETHER_PROVISIONING);
        intent.putExtra(EXTRA_ADD_TETHER_TYPE, type);
        intent.putExtra(EXTRA_PROVISION_CALLBACK, receiver);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // Used by the SIM card change observation code.
    // TODO: De-duplicate with above code, where possible.
    private void startProvisionIntent(int tetherType) {
        final Intent startProvIntent = new Intent();
        startProvIntent.putExtra(EXTRA_ADD_TETHER_TYPE, tetherType);
        startProvIntent.putExtra(EXTRA_RUN_PROVISION, true);
        startProvIntent.setComponent(TETHER_SERVICE);
        mContext.startServiceAsUser(startProvIntent, UserHandle.CURRENT);
    }

    public void scheduleProvisioningRechecks(int type) {
        Intent intent = new Intent();
        intent.putExtra(EXTRA_ADD_TETHER_TYPE, type);
        intent.putExtra(EXTRA_SET_ALARM, true);
        intent.setComponent(TETHER_SERVICE);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.startServiceAsUser(intent, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void cancelTetherProvisioningRechecks(int type) {
        Intent intent = new Intent();
        intent.putExtra(EXTRA_REM_TETHER_TYPE, type);
        intent.setComponent(TETHER_SERVICE);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.startServiceAsUser(intent, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private ResultReceiver buildProxyReceiver(int type, final ResultReceiver receiver) {
        ResultReceiver rr = new ResultReceiver(mMasterHandler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                int updatedCacheValue = updateEntitlementCacheValue(type, resultCode);
                receiver.send(updatedCacheValue, null);
            }
        };

        return writeToParcel(rr);
    }

    private ResultReceiver writeToParcel(final ResultReceiver receiver) {
        // This is necessary to avoid unmarshalling issues when sending the receiver
        // across processes.
        Parcel parcel = Parcel.obtain();
        receiver.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ResultReceiver receiverForSending = ResultReceiver.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return receiverForSending;
    }

    /**
     * Update the last entitlement value to internal cache
     *
     * @param type tethering type from ConnectivityManager.TETHERING_{@code *}
     * @param resultCode last entitlement value
     * @return the last updated entitlement value
     */
    public int updateEntitlementCacheValue(int type, int resultCode) {
        if (DBG) {
            Log.d(TAG, "updateEntitlementCacheValue: " + type + ", result: " + resultCode);
        }
        synchronized (mEntitlementCacheValue) {
            if (resultCode == TETHER_ERROR_NO_ERROR) {
                mEntitlementCacheValue.put(type, resultCode);
                return resultCode;
            } else {
                mEntitlementCacheValue.put(type, TETHER_ERROR_PROVISION_FAILED);
                return TETHER_ERROR_PROVISION_FAILED;
            }
        }
    }

    /** Get the last value of the tethering entitlement check. */
    public void getLatestTetheringEntitlementResult(int downstream, ResultReceiver receiver,
            boolean showEntitlementUi) {
        if (!isTetherProvisioningRequired()) {
            receiver.send(TETHER_ERROR_NO_ERROR, null);
            return;
        }

        final int cacheValue;
        synchronized (mEntitlementCacheValue) {
            cacheValue = mEntitlementCacheValue.get(
                downstream, TETHER_ERROR_ENTITLEMENT_UNKONWN);
        }
        if (cacheValue == TETHER_ERROR_NO_ERROR || !showEntitlementUi) {
            receiver.send(cacheValue, null);
        } else {
            ResultReceiver proxy = buildProxyReceiver(downstream, receiver);
            runUiTetherProvisioning(downstream, proxy);
        }
    }
}
