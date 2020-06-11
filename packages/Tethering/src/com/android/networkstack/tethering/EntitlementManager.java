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

package com.android.networkstack.tethering;

import static android.net.TetheringConstants.EXTRA_ADD_TETHER_TYPE;
import static android.net.TetheringConstants.EXTRA_PROVISION_CALLBACK;
import static android.net.TetheringConstants.EXTRA_RUN_PROVISION;
import static android.net.TetheringConstants.EXTRA_TETHER_PROVISIONING_RESPONSE;
import static android.net.TetheringConstants.EXTRA_TETHER_SILENT_PROVISIONING_ACTION;
import static android.net.TetheringConstants.EXTRA_TETHER_SUBID;
import static android.net.TetheringConstants.EXTRA_TETHER_UI_PROVISIONING_APP_NAME;
import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringManager.TETHERING_INVALID;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHER_ERROR_ENTITLEMENT_UNKNOWN;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_PROVISIONING_FAILED;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.util.SharedLog;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.BitSet;

/**
 * Re-check tethering provisioning for enabled downstream tether types.
 * Reference TetheringManager.TETHERING_{@code *} for each tether type.
 *
 * All methods of this class must be accessed from the thread of tethering
 * state machine.
 * @hide
 */
public class EntitlementManager {
    private static final String TAG = EntitlementManager.class.getSimpleName();
    private static final boolean DBG = false;

    @VisibleForTesting
    protected static final String DISABLE_PROVISIONING_SYSPROP_KEY = "net.tethering.noprovisioning";
    private static final String ACTION_PROVISIONING_ALARM =
            "com.android.networkstack.tethering.PROVISIONING_RECHECK_ALARM";

    private final ComponentName mSilentProvisioningService;
    private static final int MS_PER_HOUR = 60 * 60 * 1000;
    private static final int DUMP_TIMEOUT = 10_000;

    // The BitSet is the bit map of each enabled downstream types, ex:
    // {@link TetheringManager.TETHERING_WIFI}
    // {@link TetheringManager.TETHERING_USB}
    // {@link TetheringManager.TETHERING_BLUETOOTH}
    private final BitSet mCurrentDownstreams;
    private final BitSet mExemptedDownstreams;
    private final Context mContext;
    private final SharedLog mLog;
    private final SparseIntArray mEntitlementCacheValue;
    private final Handler mHandler;
    // Key: TetheringManager.TETHERING_*(downstream).
    // Value: TetheringManager.TETHER_ERROR_{NO_ERROR or PROVISION_FAILED}(provisioning result).
    private final SparseIntArray mCurrentEntitlementResults;
    private final Runnable mPermissionChangeCallback;
    private PendingIntent mProvisioningRecheckAlarm;
    private boolean mLastCellularUpstreamPermitted = true;
    private boolean mUsingCellularAsUpstream = false;
    private boolean mNeedReRunProvisioningUi = false;
    private OnUiEntitlementFailedListener mListener;
    private TetheringConfigurationFetcher mFetcher;

    public EntitlementManager(Context ctx, Handler h, SharedLog log,
            Runnable callback) {
        mContext = ctx;
        mLog = log.forSubComponent(TAG);
        mCurrentDownstreams = new BitSet();
        mExemptedDownstreams = new BitSet();
        mCurrentEntitlementResults = new SparseIntArray();
        mEntitlementCacheValue = new SparseIntArray();
        mPermissionChangeCallback = callback;
        mHandler = h;
        mContext.registerReceiver(mReceiver, new IntentFilter(ACTION_PROVISIONING_ALARM),
                null, mHandler);
        mSilentProvisioningService = ComponentName.unflattenFromString(
                mContext.getResources().getString(R.string.config_wifi_tether_enable));
    }

    public void setOnUiEntitlementFailedListener(final OnUiEntitlementFailedListener listener) {
        mListener = listener;
    }

    /** Callback fired when UI entitlement failed. */
    public interface OnUiEntitlementFailedListener {
        /**
         * Ui entitlement check fails in |downstream|.
         *
         * @param downstream tethering type from TetheringManager.TETHERING_{@code *}.
         */
        void onUiEntitlementFailed(int downstream);
    }

    public void setTetheringConfigurationFetcher(final TetheringConfigurationFetcher fetcher) {
        mFetcher = fetcher;
    }

    /** Interface to fetch TetheringConfiguration. */
    public interface TetheringConfigurationFetcher {
        /**
         * Fetch current tethering configuration. This will be called to ensure whether entitlement
         * check is needed.
         * @return TetheringConfiguration instance.
         */
        TetheringConfiguration fetchTetheringConfiguration();
    }

    /**
     * Check if cellular upstream is permitted.
     */
    public boolean isCellularUpstreamPermitted() {
        final TetheringConfiguration config = mFetcher.fetchTetheringConfiguration();

        return isCellularUpstreamPermitted(config);
    }

    private boolean isCellularUpstreamPermitted(final TetheringConfiguration config) {
        if (!isTetherProvisioningRequired(config)) return true;

        // If provisioning is required and EntitlementManager doesn't know any downstreams, cellular
        // upstream should not be enabled. Enable cellular upstream for exempted downstreams only
        // when there is no non-exempted downstream.
        if (mCurrentDownstreams.isEmpty()) return !mExemptedDownstreams.isEmpty();

        return mCurrentEntitlementResults.indexOfValue(TETHER_ERROR_NO_ERROR) > -1;
    }

    /**
     * Set exempted downstream type. If there is only exempted downstream type active,
     * corresponding entitlement check will not be run and cellular upstream will be permitted
     * by default. If a privileged app enables tethering without a provisioning check, and then
     * another app enables tethering of the same type but does not disable the provisioning check,
     * then the downstream immediately loses exempt status and a provisioning check is run.
     * If any non-exempted downstream type is active, the cellular upstream will be gated by the
     * result of entitlement check from non-exempted downstreams. If entitlement check is still
     * in progress on non-exempt downstreams, ceullar upstream would default be disabled. When any
     * non-exempted downstream gets positive entitlement result, ceullar upstream will be enabled.
     */
    public void setExemptedDownstreamType(final int type) {
        mExemptedDownstreams.set(type, true);
    }

    /**
     * This is called when tethering starts.
     * Launch provisioning app if upstream is cellular.
     *
     * @param downstreamType tethering type from TetheringManager.TETHERING_{@code *}
     * @param showProvisioningUi a boolean indicating whether to show the
     *        provisioning app UI if there is one.
     */
    public void startProvisioningIfNeeded(int downstreamType, boolean showProvisioningUi) {
        if (!isValidDownstreamType(downstreamType)) return;

        mCurrentDownstreams.set(downstreamType, true);

        mExemptedDownstreams.set(downstreamType, false);

        final TetheringConfiguration config = mFetcher.fetchTetheringConfiguration();
        if (!isTetherProvisioningRequired(config)) return;

        // If upstream is not cellular, provisioning app would not be launched
        // till upstream change to cellular.
        if (mUsingCellularAsUpstream) {
            if (showProvisioningUi) {
                runUiTetherProvisioning(downstreamType, config);
            } else {
                runSilentTetherProvisioning(downstreamType, config);
            }
            mNeedReRunProvisioningUi = false;
        } else {
            mNeedReRunProvisioningUi |= showProvisioningUi;
        }
    }

    /**
     * Tell EntitlementManager that a given type of tethering has been disabled
     *
     * @param type tethering type from TetheringManager.TETHERING_{@code *}
     */
    public void stopProvisioningIfNeeded(int downstreamType) {
        if (!isValidDownstreamType(downstreamType)) return;

        mCurrentDownstreams.set(downstreamType, false);
        // There are lurking bugs where the notion of "provisioning required" or
        // "tethering supported" may change without without tethering being notified properly.
        // Remove the mapping all the time no matter provisioning is required or not.
        removeDownstreamMapping(downstreamType);
        mExemptedDownstreams.set(downstreamType, false);
    }

    /**
     * Notify EntitlementManager if upstream is cellular or not.
     *
     * @param isCellular whether tethering upstream is cellular.
     */
    public void notifyUpstream(boolean isCellular) {
        if (DBG) {
            mLog.i("notifyUpstream: " + isCellular
                    + ", mLastCellularUpstreamPermitted: " + mLastCellularUpstreamPermitted
                    + ", mNeedReRunProvisioningUi: " + mNeedReRunProvisioningUi);
        }
        mUsingCellularAsUpstream = isCellular;

        if (mUsingCellularAsUpstream) {
            final TetheringConfiguration config = mFetcher.fetchTetheringConfiguration();
            maybeRunProvisioning(config);
        }
    }

    /** Run provisioning if needed */
    public void maybeRunProvisioning() {
        final TetheringConfiguration config = mFetcher.fetchTetheringConfiguration();
        maybeRunProvisioning(config);
    }

    private void maybeRunProvisioning(final TetheringConfiguration config) {
        if (mCurrentDownstreams.isEmpty() || !isTetherProvisioningRequired(config)) {
            return;
        }

        // Whenever any entitlement value changes, all downstreams will re-evaluate whether they
        // are allowed. Therefore even if the silent check here ends in a failure and the UI later
        // yields success, then the downstream that got a failure will re-evaluate as a result of
        // the change and get the new correct value.
        for (int downstream = mCurrentDownstreams.nextSetBit(0); downstream >= 0;
                downstream = mCurrentDownstreams.nextSetBit(downstream + 1)) {
            if (mCurrentEntitlementResults.indexOfKey(downstream) < 0) {
                if (mNeedReRunProvisioningUi) {
                    mNeedReRunProvisioningUi = false;
                    runUiTetherProvisioning(downstream, config);
                } else {
                    runSilentTetherProvisioning(downstream, config);
                }
            }
        }
    }

    /**
     * Check if the device requires a provisioning check in order to enable tethering.
     *
     * @param config an object that encapsulates the various tethering configuration elements.
     * @return a boolean - {@code true} indicating tether provisioning is required by the carrier.
     */
    @VisibleForTesting
    protected boolean isTetherProvisioningRequired(final TetheringConfiguration config) {
        if (SystemProperties.getBoolean(DISABLE_PROVISIONING_SYSPROP_KEY, false)
                || config.provisioningApp.length == 0) {
            return false;
        }
        if (carrierConfigAffirmsEntitlementCheckNotRequired(config)) {
            return false;
        }
        return (config.provisioningApp.length == 2);
    }

    /**
     * Re-check tethering provisioning for all enabled tether types.
     * Reference TetheringManager.TETHERING_{@code *} for each tether type.
     *
     * @param config an object that encapsulates the various tethering configuration elements.
     * Note: this method is only called from TetherMaster on the handler thread.
     * If there are new callers from different threads, the logic should move to
     * masterHandler to avoid race conditions.
     */
    public void reevaluateSimCardProvisioning(final TetheringConfiguration config) {
        if (DBG) mLog.i("reevaluateSimCardProvisioning");

        if (!mHandler.getLooper().isCurrentThread()) {
            // Except for test, this log should not appear in normal flow.
            mLog.log("reevaluateSimCardProvisioning() don't run in TetherMaster thread");
        }
        mEntitlementCacheValue.clear();
        mCurrentEntitlementResults.clear();

        // TODO: refine provisioning check to isTetherProvisioningRequired() ??
        if (!config.hasMobileHotspotProvisionApp()
                || carrierConfigAffirmsEntitlementCheckNotRequired(config)) {
            evaluateCellularPermission(config);
            return;
        }

        if (mUsingCellularAsUpstream) {
            maybeRunProvisioning(config);
        }
    }

    /**
     * Get carrier configuration bundle.
     * @param config an object that encapsulates the various tethering configuration elements.
     * */
    public PersistableBundle getCarrierConfig(final TetheringConfiguration config) {
        final CarrierConfigManager configManager = (CarrierConfigManager) mContext
                .getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager == null) return null;

        final PersistableBundle carrierConfig = configManager.getConfigForSubId(
                config.activeDataSubId);

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
    private boolean carrierConfigAffirmsEntitlementCheckNotRequired(
            final TetheringConfiguration config) {
        // Check carrier config for entitlement checks
        final PersistableBundle carrierConfig = getCarrierConfig(config);
        if (carrierConfig == null) return false;

        // A CarrierConfigManager was found and it has a config.
        final boolean isEntitlementCheckRequired = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_REQUIRE_ENTITLEMENT_CHECKS_BOOL);
        return !isEntitlementCheckRequired;
    }

    /**
     * Run no UI tethering provisioning check.
     * @param type tethering type from TetheringManager.TETHERING_{@code *}
     * @param subId default data subscription ID.
     */
    @VisibleForTesting
    protected Intent runSilentTetherProvisioning(int type, final TetheringConfiguration config) {
        if (DBG) mLog.i("runSilentTetherProvisioning: " + type);
        // For silent provisioning, settings would stop tethering when entitlement fail.
        ResultReceiver receiver = buildProxyReceiver(type, false/* notifyFail */, null);

        Intent intent = new Intent();
        intent.putExtra(EXTRA_ADD_TETHER_TYPE, type);
        intent.putExtra(EXTRA_RUN_PROVISION, true);
        intent.putExtra(EXTRA_TETHER_SILENT_PROVISIONING_ACTION, config.provisioningAppNoUi);
        intent.putExtra(EXTRA_TETHER_PROVISIONING_RESPONSE, config.provisioningResponse);
        intent.putExtra(EXTRA_PROVISION_CALLBACK, receiver);
        intent.putExtra(EXTRA_TETHER_SUBID, config.activeDataSubId);
        intent.setComponent(mSilentProvisioningService);
        // Only admin user can change tethering and SilentTetherProvisioning don't need to
        // show UI, it is fine to always start setting's background service as system user.
        mContext.startService(intent);
        return intent;
    }

    private void runUiTetherProvisioning(int type, final TetheringConfiguration config) {
        ResultReceiver receiver = buildProxyReceiver(type, true/* notifyFail */, null);
        runUiTetherProvisioning(type, config, receiver);
    }

    /**
     * Run the UI-enabled tethering provisioning check.
     * @param type tethering type from TetheringManager.TETHERING_{@code *}
     * @param subId default data subscription ID.
     * @param receiver to receive entitlement check result.
     */
    @VisibleForTesting
    protected Intent runUiTetherProvisioning(int type, final TetheringConfiguration config,
            ResultReceiver receiver) {
        if (DBG) mLog.i("runUiTetherProvisioning: " + type);

        Intent intent = new Intent(Settings.ACTION_TETHER_PROVISIONING_UI);
        intent.putExtra(EXTRA_ADD_TETHER_TYPE, type);
        intent.putExtra(EXTRA_TETHER_UI_PROVISIONING_APP_NAME, config.provisioningApp);
        intent.putExtra(EXTRA_PROVISION_CALLBACK, receiver);
        intent.putExtra(EXTRA_TETHER_SUBID, config.activeDataSubId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Only launch entitlement UI for system user. Entitlement UI should not appear for other
        // user because only admin user is allowed to change tethering.
        mContext.startActivity(intent);
        return intent;
    }

    // Not needed to check if this don't run on the handler thread because it's private.
    private void scheduleProvisioningRechecks(final TetheringConfiguration config) {
        if (mProvisioningRecheckAlarm == null) {
            final int period = config.provisioningCheckPeriod;
            if (period <= 0) return;

            Intent intent = new Intent(ACTION_PROVISIONING_ALARM);
            mProvisioningRecheckAlarm = PendingIntent.getBroadcast(mContext, 0, intent, 0);
            AlarmManager alarmManager = (AlarmManager) mContext.getSystemService(
                    Context.ALARM_SERVICE);
            long periodMs = period * MS_PER_HOUR;
            long firstAlarmTime = SystemClock.elapsedRealtime() + periodMs;
            alarmManager.setRepeating(AlarmManager.ELAPSED_REALTIME, firstAlarmTime, periodMs,
                    mProvisioningRecheckAlarm);
        }
    }

    private void cancelTetherProvisioningRechecks() {
        if (mProvisioningRecheckAlarm != null) {
            AlarmManager alarmManager = (AlarmManager) mContext.getSystemService(
                    Context.ALARM_SERVICE);
            alarmManager.cancel(mProvisioningRecheckAlarm);
            mProvisioningRecheckAlarm = null;
        }
    }

    private void evaluateCellularPermission(final TetheringConfiguration config) {
        final boolean permitted = isCellularUpstreamPermitted(config);

        if (DBG) {
            mLog.i("Cellular permission change from " + mLastCellularUpstreamPermitted
                    + " to " + permitted);
        }

        if (mLastCellularUpstreamPermitted != permitted) {
            mLog.log("Cellular permission change: " + permitted);
            mPermissionChangeCallback.run();
        }
        // Only schedule periodic re-check when tether is provisioned
        // and the result is ok.
        if (permitted && mCurrentEntitlementResults.size() > 0) {
            scheduleProvisioningRechecks(config);
        } else {
            cancelTetherProvisioningRechecks();
        }
        mLastCellularUpstreamPermitted = permitted;
    }

    /**
     * Add the mapping between provisioning result and tethering type.
     * Notify UpstreamNetworkMonitor if Cellular permission changes.
     *
     * @param type tethering type from TetheringManager.TETHERING_{@code *}
     * @param resultCode Provisioning result
     */
    protected void addDownstreamMapping(int type, int resultCode) {
        mLog.i("addDownstreamMapping: " + type + ", result: " + resultCode
                + " ,TetherTypeRequested: " + mCurrentDownstreams.get(type));
        if (!mCurrentDownstreams.get(type)) return;

        mCurrentEntitlementResults.put(type, resultCode);
        final TetheringConfiguration config = mFetcher.fetchTetheringConfiguration();
        evaluateCellularPermission(config);
    }

    /**
     * Remove the mapping for input tethering type.
     * @param type tethering type from TetheringManager.TETHERING_{@code *}
     */
    protected void removeDownstreamMapping(int type) {
        mLog.i("removeDownstreamMapping: " + type);
        mCurrentEntitlementResults.delete(type);
        final TetheringConfiguration config = mFetcher.fetchTetheringConfiguration();
        evaluateCellularPermission(config);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_PROVISIONING_ALARM.equals(intent.getAction())) {
                mLog.log("Received provisioning alarm");
                final TetheringConfiguration config = mFetcher.fetchTetheringConfiguration();
                reevaluateSimCardProvisioning(config);
            }
        }
    };

    private static boolean isValidDownstreamType(int type) {
        switch (type) {
            case TETHERING_BLUETOOTH:
            case TETHERING_ETHERNET:
            case TETHERING_USB:
            case TETHERING_WIFI:
                return true;
            default:
                return false;
        }
    }

    /**
     * Dump the infromation of EntitlementManager.
     * @param pw {@link PrintWriter} is used to print formatted
     */
    public void dump(PrintWriter pw) {
        final ConditionVariable mWaiting = new ConditionVariable();
        mHandler.post(() -> {
            pw.print("isCellularUpstreamPermitted: ");
            pw.println(isCellularUpstreamPermitted());
            for (int type = mCurrentDownstreams.nextSetBit(0); type >= 0;
                    type = mCurrentDownstreams.nextSetBit(type + 1)) {
                pw.print("Type: ");
                pw.print(typeString(type));
                if (mCurrentEntitlementResults.indexOfKey(type) > -1) {
                    pw.print(", Value: ");
                    pw.println(errorString(mCurrentEntitlementResults.get(type)));
                } else {
                    pw.println(", Value: empty");
                }
            }
            mWaiting.open();
        });
        if (!mWaiting.block(DUMP_TIMEOUT)) {
            pw.println("... dump timed out after " + DUMP_TIMEOUT + "ms");
        }
        pw.print("Exempted: [");
        for (int type = mExemptedDownstreams.nextSetBit(0); type >= 0;
                type = mExemptedDownstreams.nextSetBit(type + 1)) {
            pw.print(typeString(type));
            pw.print(", ");
        }
        pw.println("]");
    }

    private static String typeString(int type) {
        switch (type) {
            case TETHERING_BLUETOOTH: return "TETHERING_BLUETOOTH";
            case TETHERING_INVALID: return "TETHERING_INVALID";
            case TETHERING_USB: return "TETHERING_USB";
            case TETHERING_WIFI: return "TETHERING_WIFI";
            default:
                return String.format("TETHERING UNKNOWN TYPE (%d)", type);
        }
    }

    private static String errorString(int value) {
        switch (value) {
            case TETHER_ERROR_ENTITLEMENT_UNKNOWN: return "TETHER_ERROR_ENTITLEMENT_UNKONWN";
            case TETHER_ERROR_NO_ERROR: return "TETHER_ERROR_NO_ERROR";
            case TETHER_ERROR_PROVISIONING_FAILED: return "TETHER_ERROR_PROVISIONING_FAILED";
            default:
                return String.format("UNKNOWN ERROR (%d)", value);
        }
    }

    private ResultReceiver buildProxyReceiver(int type, boolean notifyFail,
            final ResultReceiver receiver) {
        ResultReceiver rr = new ResultReceiver(mHandler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                int updatedCacheValue = updateEntitlementCacheValue(type, resultCode);
                addDownstreamMapping(type, updatedCacheValue);
                if (updatedCacheValue == TETHER_ERROR_PROVISIONING_FAILED && notifyFail) {
                    mListener.onUiEntitlementFailed(type);
                }
                if (receiver != null) receiver.send(updatedCacheValue, null);
            }
        };

        return writeToParcel(rr);
    }

    // Instances of ResultReceiver need to be public classes for remote processes to be able
    // to load them (otherwise, ClassNotFoundException). For private classes, this method
    // performs a trick : round-trip parceling any instance of ResultReceiver will return a
    // vanilla instance of ResultReceiver sharing the binder token with the original receiver.
    // The binder token has a reference to the original instance of the private class and will
    // still call its methods, and can be sent over. However it cannot be used for anything
    // else than sending over a Binder call.
    // While round-trip parceling is not great, there is currently no other way of generating
    // a vanilla instance of ResultReceiver because all its fields are private.
    private ResultReceiver writeToParcel(final ResultReceiver receiver) {
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
     * @param type tethering type from TetheringManager.TETHERING_{@code *}
     * @param resultCode last entitlement value
     * @return the last updated entitlement value
     */
    private int updateEntitlementCacheValue(int type, int resultCode) {
        if (DBG) {
            mLog.i("updateEntitlementCacheValue: " + type + ", result: " + resultCode);
        }
        if (resultCode == TETHER_ERROR_NO_ERROR) {
            mEntitlementCacheValue.put(type, resultCode);
            return resultCode;
        } else {
            mEntitlementCacheValue.put(type, TETHER_ERROR_PROVISIONING_FAILED);
            return TETHER_ERROR_PROVISIONING_FAILED;
        }
    }

    /** Get the last value of the tethering entitlement check. */
    public void requestLatestTetheringEntitlementResult(int downstream, ResultReceiver receiver,
            boolean showEntitlementUi) {
        if (!isValidDownstreamType(downstream)) {
            receiver.send(TETHER_ERROR_ENTITLEMENT_UNKNOWN, null);
            return;
        }

        final TetheringConfiguration config = mFetcher.fetchTetheringConfiguration();
        if (!isTetherProvisioningRequired(config)) {
            receiver.send(TETHER_ERROR_NO_ERROR, null);
            return;
        }

        final int cacheValue = mEntitlementCacheValue.get(
                downstream, TETHER_ERROR_ENTITLEMENT_UNKNOWN);
        if (cacheValue == TETHER_ERROR_NO_ERROR || !showEntitlementUi) {
            receiver.send(cacheValue, null);
        } else {
            ResultReceiver proxy = buildProxyReceiver(downstream, false/* notifyFail */, receiver);
            runUiTetherProvisioning(downstream, config, proxy);
        }
    }
}
