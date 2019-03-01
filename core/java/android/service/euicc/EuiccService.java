/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.service.euicc;

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccInfo;
import android.telephony.euicc.EuiccManager.OtaStatus;
import android.util.ArraySet;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service interface linking the system with an eUICC local profile assistant (LPA) application.
 *
 * <p>An LPA consists of two separate components (which may both be implemented in the same APK):
 * the LPA backend, and the LPA UI or LUI.
 *
 * <p>To implement the LPA backend, you must extend this class and declare this service in your
 * manifest file. The service must require the
 * {@link android.Manifest.permission#BIND_EUICC_SERVICE} permission and include an intent filter
 * with the {@link #EUICC_SERVICE_INTERFACE} action. It's suggested that the priority of the intent
 * filter to be set to a non-zero value in case multiple implementations are present on the device.
 * See the below example. Note that there will be problem if two LPAs are present and they have the
 * same priority.
 * Example:
 *
 * <pre>{@code
 * <service android:name=".MyEuiccService"
 *          android:permission="android.permission.BIND_EUICC_SERVICE">
 *     <intent-filter android:priority="100">
 *         <action android:name="android.service.euicc.EuiccService" />
 *     </intent-filter>
 * </service>
 * }</pre>
 *
 * <p>To implement the LUI, you must provide an activity for the following actions:
 *
 * <ul>
 * <li>{@link #ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS}
 * <li>{@link #ACTION_PROVISION_EMBEDDED_SUBSCRIPTION}
 * </ul>
 *
 * <p>As with the service, each activity must require the
 * {@link android.Manifest.permission#BIND_EUICC_SERVICE} permission. Each should have an intent
 * filter with the appropriate action, the {@link #CATEGORY_EUICC_UI} category, and a non-zero
 * priority.
 *
 * <p>Old implementations of EuiccService may support passing in slot IDs equal to
 * {@link android.telephony.SubscriptionManager#INVALID_SIM_SLOT_INDEX}, which allows the LPA to
 * decide which eUICC to target when there are multiple eUICCs. This behavior is not supported in
 * Android Q or later.
 *
 * @hide
 */
@SystemApi
public abstract class EuiccService extends Service {
    private static final String TAG = "EuiccService";

    /** Action which must be included in this service's intent filter. */
    public static final String EUICC_SERVICE_INTERFACE = "android.service.euicc.EuiccService";

    /** Category which must be defined to all UI actions, for efficient lookup. */
    public static final String CATEGORY_EUICC_UI = "android.service.euicc.category.EUICC_UI";

    // LUI actions. These are passthroughs of the corresponding EuiccManager actions.

    /**
     * @see android.telephony.euicc.EuiccManager#ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS
     * The difference is this one is used by system to bring up the LUI.
     */
    public static final String ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS =
            "android.service.euicc.action.MANAGE_EMBEDDED_SUBSCRIPTIONS";

    /** @see android.telephony.euicc.EuiccManager#ACTION_PROVISION_EMBEDDED_SUBSCRIPTION */
    public static final String ACTION_PROVISION_EMBEDDED_SUBSCRIPTION =
            "android.service.euicc.action.PROVISION_EMBEDDED_SUBSCRIPTION";

    /** @see android.telephony.euicc.EuiccManager#ACTION_TOGGLE_SUBSCRIPTION_PRIVILEGED */
    public static final String ACTION_TOGGLE_SUBSCRIPTION_PRIVILEGED =
            "android.service.euicc.action.TOGGLE_SUBSCRIPTION_PRIVILEGED";

    /** @see android.telephony.euicc.EuiccManager#ACTION_DELETE_SUBSCRIPTION_PRIVILEGED */
    public static final String ACTION_DELETE_SUBSCRIPTION_PRIVILEGED =
            "android.service.euicc.action.DELETE_SUBSCRIPTION_PRIVILEGED";

    /** @see android.telephony.euicc.EuiccManager#ACTION_RENAME_SUBSCRIPTION_PRIVILEGED */
    public static final String ACTION_RENAME_SUBSCRIPTION_PRIVILEGED =
            "android.service.euicc.action.RENAME_SUBSCRIPTION_PRIVILEGED";

    // LUI resolution actions. These are called by the platform to resolve errors in situations that
    // require user interaction.
    // TODO(b/33075886): Define extras for any input parameters to these dialogs once they are
    // more scoped out.
    /**
     * Alert the user that this action will result in an active SIM being deactivated.
     * To implement the LUI triggered by the system, you need to define this in AndroidManifest.xml.
     */
    public static final String ACTION_RESOLVE_DEACTIVATE_SIM =
            "android.service.euicc.action.RESOLVE_DEACTIVATE_SIM";
    /**
     * Alert the user about a download/switch being done for an app that doesn't currently have
     * carrier privileges.
     */
    public static final String ACTION_RESOLVE_NO_PRIVILEGES =
            "android.service.euicc.action.RESOLVE_NO_PRIVILEGES";

    /**
     * Ask the user to input carrier confirmation code.
     *
     * @deprecated From Q, the resolvable errors happened in the download step are presented as
     * bit map in {@link #EXTRA_RESOLVABLE_ERRORS}. The corresponding action would be
     * {@link #ACTION_RESOLVE_RESOLVABLE_ERRORS}.
     */
    @Deprecated
    public static final String ACTION_RESOLVE_CONFIRMATION_CODE =
            "android.service.euicc.action.RESOLVE_CONFIRMATION_CODE";

    /** Ask the user to resolve all the resolvable errors. */
    public static final String ACTION_RESOLVE_RESOLVABLE_ERRORS =
            "android.service.euicc.action.RESOLVE_RESOLVABLE_ERRORS";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "RESOLVABLE_ERROR_" }, value = {
            RESOLVABLE_ERROR_CONFIRMATION_CODE,
            RESOLVABLE_ERROR_POLICY_RULES,
    })
    public @interface ResolvableError {}

    /**
     * Possible value for the bit map of resolvable errors indicating the download process needs
     * the user to input confirmation code.
     */
    public static final int RESOLVABLE_ERROR_CONFIRMATION_CODE = 1 << 0;
    /**
     * Possible value for the bit map of resolvable errors indicating the download process needs
     * the user's consent to allow profile policy rules.
     */
    public static final int RESOLVABLE_ERROR_POLICY_RULES = 1 << 1;

    /**
     * Intent extra set for resolution requests containing the package name of the calling app.
     * This is used by the above actions including ACTION_RESOLVE_DEACTIVATE_SIM,
     * ACTION_RESOLVE_NO_PRIVILEGES and ACTION_RESOLVE_RESOLVABLE_ERRORS.
     */
    public static final String EXTRA_RESOLUTION_CALLING_PACKAGE =
            "android.service.euicc.extra.RESOLUTION_CALLING_PACKAGE";

    /**
     * Intent extra set for resolution requests containing the list of resolvable errors to be
     * resolved. Each resolvable error is an integer. Its possible values include:
     * <UL>
     * <LI>{@link #RESOLVABLE_ERROR_CONFIRMATION_CODE}
     * <LI>{@link #RESOLVABLE_ERROR_POLICY_RULES}
     * </UL>
     */
    public static final String EXTRA_RESOLVABLE_ERRORS =
            "android.service.euicc.extra.RESOLVABLE_ERRORS";

    /**
     * Intent extra set for resolution requests containing a boolean indicating whether to ask the
     * user to retry another confirmation code.
     */
    public static final String EXTRA_RESOLUTION_CONFIRMATION_CODE_RETRIED =
            "android.service.euicc.extra.RESOLUTION_CONFIRMATION_CODE_RETRIED";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "RESULT_" }, value = {
            RESULT_OK,
            RESULT_MUST_DEACTIVATE_SIM,
            RESULT_RESOLVABLE_ERRORS,
            RESULT_NEED_CONFIRMATION_CODE,
            RESULT_FIRST_USER,
    })
    public @interface Result {}

    /** Result code for a successful operation. */
    public static final int RESULT_OK = 0;
    /** Result code indicating that an active SIM must be deactivated to perform the operation. */
    public static final int RESULT_MUST_DEACTIVATE_SIM = -1;
    /** Result code indicating that the user must resolve resolvable errors. */
    public static final int RESULT_RESOLVABLE_ERRORS = -2;
    /**
     * Result code indicating that the user must input a carrier confirmation code.
     *
     * @deprecated From Q, the resolvable errors happened in the download step are presented as
     * bit map in {@link #EXTRA_RESOLVABLE_ERRORS}. The corresponding result would be
     * {@link #RESULT_RESOLVABLE_ERRORS}.
     */
    @Deprecated
    public static final int RESULT_NEED_CONFIRMATION_CODE = -2;
    // New predefined codes should have negative values.

    /** Start of implementation-specific error results. */
    public static final int RESULT_FIRST_USER = 1;

    /**
     * List of all valid resolution actions for validation purposes.
     * @hide
     */
    public static final ArraySet<String> RESOLUTION_ACTIONS;
    static {
        RESOLUTION_ACTIONS = new ArraySet<>();
        RESOLUTION_ACTIONS.add(EuiccService.ACTION_RESOLVE_DEACTIVATE_SIM);
        RESOLUTION_ACTIONS.add(EuiccService.ACTION_RESOLVE_NO_PRIVILEGES);
        RESOLUTION_ACTIONS.add(EuiccService.ACTION_RESOLVE_RESOLVABLE_ERRORS);
    }

    /**
     * Boolean extra for resolution actions indicating whether the user granted consent.
     * This is used and set by the implementation and used in {@code EuiccOperation}.
     */
    public static final String EXTRA_RESOLUTION_CONSENT =
            "android.service.euicc.extra.RESOLUTION_CONSENT";
    /**
     * String extra for resolution actions indicating the carrier confirmation code.
     * This is used and set by the implementation and used in {@code EuiccOperation}.
     */
    public static final String EXTRA_RESOLUTION_CONFIRMATION_CODE =
            "android.service.euicc.extra.RESOLUTION_CONFIRMATION_CODE";
    /**
     * String extra for resolution actions indicating whether the user allows policy rules.
     * This is used and set by the implementation and used in {@code EuiccOperation}.
     */
    public static final String EXTRA_RESOLUTION_ALLOW_POLICY_RULES =
            "android.service.euicc.extra.RESOLUTION_ALLOW_POLICY_RULES";

    private final IEuiccService.Stub mStubWrapper;

    private ThreadPoolExecutor mExecutor;

    public EuiccService() {
        mStubWrapper = new IEuiccServiceWrapper();
    }

    @Override
    @CallSuper
    public void onCreate() {
        super.onCreate();
        // We use a oneway AIDL interface to avoid blocking phone process binder threads on IPCs to
        // an external process, but doing so means the requests are serialized by binder, which is
        // not desired. Spin up a background thread pool to allow requests to be parallelized.
        // TODO(b/38206971): Consider removing this if basic card-level functions like listing
        // profiles are moved to the platform.
        mExecutor = new ThreadPoolExecutor(
                4 /* corePoolSize */,
                4 /* maxPoolSize */,
                30, TimeUnit.SECONDS, /* keepAliveTime */
                new LinkedBlockingQueue<>(), /* workQueue */
                new ThreadFactory() {
                    private final AtomicInteger mCount = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "EuiccService #" + mCount.getAndIncrement());
                    }
                }
        );
        mExecutor.allowCoreThreadTimeOut(true);
    }

    @Override
    @CallSuper
    public void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    /**
     * If overriding this method, call through to the super method for any unknown actions.
     * {@inheritDoc}
     */
    @Override
    @CallSuper
    public IBinder onBind(Intent intent) {
        return mStubWrapper;
    }

    /**
     * Callback class for {@link #onStartOtaIfNecessary(int, OtaStatusChangedCallback)}
     *
     * The status of OTA which can be {@code android.telephony.euicc.EuiccManager#EUICC_OTA_}
     *
     * @see IEuiccService#startOtaIfNecessary
     */
    public abstract static class OtaStatusChangedCallback {
        /** Called when OTA status is changed. */
        public abstract void onOtaStatusChanged(int status);
    }

    /**
     * Return the EID of the eUICC.
     *
     * @param slotId ID of the SIM slot being queried.
     * @return the EID.
     * @see android.telephony.euicc.EuiccManager#getEid
     */
    // TODO(b/36260308): Update doc when we have multi-SIM support.
    public abstract String onGetEid(int slotId);

    /**
     * Return the status of OTA update.
     *
     * @param slotId ID of the SIM slot to use for the operation.
     * @return The status of Euicc OTA update.
     * @see android.telephony.euicc.EuiccManager#getOtaStatus
     */
    public abstract @OtaStatus int onGetOtaStatus(int slotId);

    /**
     * Perform OTA if current OS is not the latest one.
     *
     * @param slotId ID of the SIM slot to use for the operation.
     * @param statusChangedCallback Function called when OTA status changed.
     */
    public abstract void onStartOtaIfNecessary(
            int slotId, OtaStatusChangedCallback statusChangedCallback);

    /**
     * Populate {@link DownloadableSubscription} metadata for the given downloadable subscription.
     *
     * @param slotId ID of the SIM slot to use for the operation.
     * @param subscription A subscription whose metadata needs to be populated.
     * @param forceDeactivateSim If true, and if an active SIM must be deactivated to access the
     *     eUICC, perform this action automatically. Otherwise, {@link #RESULT_MUST_DEACTIVATE_SIM)}
     *     should be returned to allow the user to consent to this operation first.
     * @return The result of the operation.
     * @see android.telephony.euicc.EuiccManager#getDownloadableSubscriptionMetadata
     */
    public abstract GetDownloadableSubscriptionMetadataResult onGetDownloadableSubscriptionMetadata(
            int slotId, DownloadableSubscription subscription, boolean forceDeactivateSim);

    /**
     * Return metadata for subscriptions which are available for download for this device.
     *
     * @param slotId ID of the SIM slot to use for the operation.
     * @param forceDeactivateSim If true, and if an active SIM must be deactivated to access the
     *     eUICC, perform this action automatically. Otherwise, {@link #RESULT_MUST_DEACTIVATE_SIM)}
     *     should be returned to allow the user to consent to this operation first.
     * @return The result of the list operation.
     * @see android.telephony.euicc.EuiccManager#getDefaultDownloadableSubscriptionList
     */
    public abstract GetDefaultDownloadableSubscriptionListResult
            onGetDefaultDownloadableSubscriptionList(int slotId, boolean forceDeactivateSim);

    /**
     * Download the given subscription.
     *
     * @param slotId ID of the SIM slot to use for the operation.
     * @param subscription The subscription to download.
     * @param switchAfterDownload If true, the subscription should be enabled upon successful
     *     download.
     * @param forceDeactivateSim If true, and if an active SIM must be deactivated to access the
     *     eUICC, perform this action automatically. Otherwise, {@link #RESULT_MUST_DEACTIVATE_SIM}
     *     should be returned to allow the user to consent to this operation first.
     * @param resolvedBundle The bundle containing information on resolved errors. It can contain
     *     a string of confirmation code for the key {@link #EXTRA_RESOLUTION_CONFIRMATION_CODE},
     *     and a boolean for key {@link #EXTRA_RESOLUTION_ALLOW_POLICY_RULES} indicating whether
     *     the user allows profile policy rules or not.
     * @return a DownloadSubscriptionResult instance including a result code, a resolvable errors
     *     bit map, and original the card Id. The result code may be one of the predefined
     *     {@code RESULT_} constants or any implementation-specific code starting with
     *     {@link #RESULT_FIRST_USER}. The resolvable error bit map can be either 0 or values
     *     defined in {@code RESOLVABLE_ERROR_}.
     * @see android.telephony.euicc.EuiccManager#downloadSubscription
     */
    public abstract DownloadSubscriptionResult onDownloadSubscription(int slotId,
            @NonNull DownloadableSubscription subscription, boolean switchAfterDownload,
            boolean forceDeactivateSim, @Nullable Bundle resolvedBundle);

    /**
     * Download the given subscription.
     *
     * @param slotId ID of the SIM slot to use for the operation.
     * @param subscription The subscription to download.
     * @param switchAfterDownload If true, the subscription should be enabled upon successful
     *     download.
     * @param forceDeactivateSim If true, and if an active SIM must be deactivated to access the
     *     eUICC, perform this action automatically. Otherwise, {@link #RESULT_MUST_DEACTIVATE_SIM}
     *     should be returned to allow the user to consent to this operation first.
     * @return the result of the download operation. May be one of the predefined {@code RESULT_}
     *     constants or any implementation-specific code starting with {@link #RESULT_FIRST_USER}.
     * @see android.telephony.euicc.EuiccManager#downloadSubscription
     *
     * @deprecated From Q, please use the above
     * {@link #onDownloadSubscription(int, DownloadableSubscription, boolean, boolean, Bundle)}.
     */
    @Deprecated public @Result int onDownloadSubscription(int slotId,
            @NonNull DownloadableSubscription subscription, boolean switchAfterDownload,
            boolean forceDeactivateSim) {
        throw new UnsupportedOperationException("onDownloadSubscription(int, "
            + "DownloadableSubscription, boolean, boolean) is deprecated.");
    }

    /**
     * Return a list of all @link EuiccProfileInfo}s.
     *
     * @param slotId ID of the SIM slot to use for the operation.
     * @return The result of the operation.
     * @see android.telephony.SubscriptionManager#getAvailableSubscriptionInfoList
     * @see android.telephony.SubscriptionManager#getAccessibleSubscriptionInfoList
     */
    public abstract @NonNull GetEuiccProfileInfoListResult onGetEuiccProfileInfoList(int slotId);

    /**
     * Return info about the eUICC chip/device.
     *
     * @param slotId ID of the SIM slot to use for the operation.
     * @return the {@link EuiccInfo} for the eUICC chip/device.
     * @see android.telephony.euicc.EuiccManager#getEuiccInfo
     */
    public abstract @NonNull EuiccInfo onGetEuiccInfo(int slotId);

    /**
     * Delete the given subscription.
     *
     * <p>If the subscription is currently active, it should be deactivated first (equivalent to a
     * physical SIM being ejected).
     *
     * @param slotId ID of the SIM slot to use for the operation.
     * @param iccid the ICCID of the subscription to delete.
     * @return the result of the delete operation. May be one of the predefined {@code RESULT_}
     *     constants or any implementation-specific code starting with {@link #RESULT_FIRST_USER}.
     * @see android.telephony.euicc.EuiccManager#deleteSubscription
     */
    public abstract @Result int onDeleteSubscription(int slotId, String iccid);

    /**
     * Switch to the given subscription.
     *
     * @param slotId ID of the SIM slot to use for the operation.
     * @param iccid the ICCID of the subscription to enable. May be null, in which case the current
     *     profile should be deactivated and no profile should be activated to replace it - this is
     *     equivalent to a physical SIM being ejected.
     * @param forceDeactivateSim If true, and if an active SIM must be deactivated to access the
     *     eUICC, perform this action automatically. Otherwise, {@link #RESULT_MUST_DEACTIVATE_SIM}
     *     should be returned to allow the user to consent to this operation first.
     * @return the result of the switch operation. May be one of the predefined {@code RESULT_}
     *     constants or any implementation-specific code starting with {@link #RESULT_FIRST_USER}.
     * @see android.telephony.euicc.EuiccManager#switchToSubscription
     */
    public abstract @Result int onSwitchToSubscription(int slotId, @Nullable String iccid,
            boolean forceDeactivateSim);

    /**
     * Update the nickname of the given subscription.
     *
     * @param slotId ID of the SIM slot to use for the operation.
     * @param iccid the ICCID of the subscription to update.
     * @param nickname the new nickname to apply.
     * @return the result of the update operation. May be one of the predefined {@code RESULT_}
     *     constants or any implementation-specific code starting with {@link #RESULT_FIRST_USER}.
     * @see android.telephony.euicc.EuiccManager#updateSubscriptionNickname
     */
    public abstract int onUpdateSubscriptionNickname(int slotId, String iccid,
            String nickname);

    /**
     * Erase all of the subscriptions on the device.
     *
     * <p>This is intended to be used for device resets. As such, the reset should be performed even
     * if an active SIM must be deactivated in order to access the eUICC.
     *
     * @param slotId ID of the SIM slot to use for the operation.
     * @return the result of the erase operation. May be one of the predefined {@code RESULT_}
     *     constants or any implementation-specific code starting with {@link #RESULT_FIRST_USER}.
     * @see android.telephony.euicc.EuiccManager#eraseSubscriptions
     */
    public abstract int onEraseSubscriptions(int slotId);

    /**
     * Ensure that subscriptions will be retained on the next factory reset.
     *
     * <p>Called directly before a factory reset. Assumes that a normal factory reset will lead to
     * profiles being erased on first boot (to cover fastboot/recovery wipes), so the implementation
     * should persist some bit that will remain accessible after the factory reset to bypass this
     * flow when this method is called.
     *
     * @param slotId ID of the SIM slot to use for the operation.
     * @return the result of the operation. May be one of the predefined {@code RESULT_} constants
     *     or any implementation-specific code starting with {@link #RESULT_FIRST_USER}.
     */
    public abstract int onRetainSubscriptionsForFactoryReset(int slotId);

    /**
     * Wrapper around IEuiccService that forwards calls to implementations of {@link EuiccService}.
     */
    private class IEuiccServiceWrapper extends IEuiccService.Stub {
        @Override
        public void downloadSubscription(int slotId, DownloadableSubscription subscription,
                boolean switchAfterDownload, boolean forceDeactivateSim, Bundle resolvedBundle,
                IDownloadSubscriptionCallback callback) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    DownloadSubscriptionResult result;
                    try {
                        result =
                            EuiccService.this.onDownloadSubscription(
                                slotId, subscription, switchAfterDownload, forceDeactivateSim,
                                resolvedBundle);
                    } catch (AbstractMethodError e) {
                        Log.w(TAG, "The new onDownloadSubscription(int, "
                                + "DownloadableSubscription, boolean, boolean, Bundle) is not "
                                + "implemented. Fall back to the old one.", e);
                        int resultCode = EuiccService.this.onDownloadSubscription(
                                slotId, subscription, switchAfterDownload, forceDeactivateSim);
                        result = new DownloadSubscriptionResult(resultCode,
                            0 /* resolvableErrors */, TelephonyManager.UNSUPPORTED_CARD_ID);
                    }
                    try {
                        callback.onComplete(result);
                    } catch (RemoteException e) {
                        // Can't communicate with the phone process; ignore.
                    }
                }
            });
        }

        @Override
        public void getEid(int slotId, IGetEidCallback callback) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    String eid = EuiccService.this.onGetEid(slotId);
                    try {
                        callback.onSuccess(eid);
                    } catch (RemoteException e) {
                        // Can't communicate with the phone process; ignore.
                    }
                }
            });
        }

        @Override
        public void startOtaIfNecessary(
                int slotId, IOtaStatusChangedCallback statusChangedCallback) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    EuiccService.this.onStartOtaIfNecessary(slotId, new OtaStatusChangedCallback() {
                        @Override
                        public void onOtaStatusChanged(int status) {
                            try {
                                statusChangedCallback.onOtaStatusChanged(status);
                            } catch (RemoteException e) {
                                // Can't communicate with the phone process; ignore.
                            }
                        }
                    });
                }
            });
        }

        @Override
        public void getOtaStatus(int slotId, IGetOtaStatusCallback callback) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    int status = EuiccService.this.onGetOtaStatus(slotId);
                    try {
                        callback.onSuccess(status);
                    } catch (RemoteException e) {
                        // Can't communicate with the phone process; ignore.
                    }
                }
            });
        }

        @Override
        public void getDownloadableSubscriptionMetadata(int slotId,
                DownloadableSubscription subscription,
                boolean forceDeactivateSim,
                IGetDownloadableSubscriptionMetadataCallback callback) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    GetDownloadableSubscriptionMetadataResult result =
                            EuiccService.this.onGetDownloadableSubscriptionMetadata(
                                    slotId, subscription, forceDeactivateSim);
                    try {
                        callback.onComplete(result);
                    } catch (RemoteException e) {
                        // Can't communicate with the phone process; ignore.
                    }
                }
            });
        }

        @Override
        public void getDefaultDownloadableSubscriptionList(int slotId, boolean forceDeactivateSim,
                IGetDefaultDownloadableSubscriptionListCallback callback) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    GetDefaultDownloadableSubscriptionListResult result =
                            EuiccService.this.onGetDefaultDownloadableSubscriptionList(
                                    slotId, forceDeactivateSim);
                    try {
                        callback.onComplete(result);
                    } catch (RemoteException e) {
                        // Can't communicate with the phone process; ignore.
                    }
                }
            });
        }

        @Override
        public void getEuiccProfileInfoList(int slotId, IGetEuiccProfileInfoListCallback callback) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    GetEuiccProfileInfoListResult result =
                            EuiccService.this.onGetEuiccProfileInfoList(slotId);
                    try {
                        callback.onComplete(result);
                    } catch (RemoteException e) {
                        // Can't communicate with the phone process; ignore.
                    }
                }
            });
        }

        @Override
        public void getEuiccInfo(int slotId, IGetEuiccInfoCallback callback) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    EuiccInfo euiccInfo = EuiccService.this.onGetEuiccInfo(slotId);
                    try {
                        callback.onSuccess(euiccInfo);
                    } catch (RemoteException e) {
                        // Can't communicate with the phone process; ignore.
                    }
                }
            });

        }

        @Override
        public void deleteSubscription(int slotId, String iccid,
                IDeleteSubscriptionCallback callback) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    int result = EuiccService.this.onDeleteSubscription(slotId, iccid);
                    try {
                        callback.onComplete(result);
                    } catch (RemoteException e) {
                        // Can't communicate with the phone process; ignore.
                    }
                }
            });
        }

        @Override
        public void switchToSubscription(int slotId, String iccid, boolean forceDeactivateSim,
                ISwitchToSubscriptionCallback callback) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    int result =
                            EuiccService.this.onSwitchToSubscription(
                                    slotId, iccid, forceDeactivateSim);
                    try {
                        callback.onComplete(result);
                    } catch (RemoteException e) {
                        // Can't communicate with the phone process; ignore.
                    }
                }
            });
        }

        @Override
        public void updateSubscriptionNickname(int slotId, String iccid, String nickname,
                IUpdateSubscriptionNicknameCallback callback) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    int result =
                            EuiccService.this.onUpdateSubscriptionNickname(slotId, iccid, nickname);
                    try {
                        callback.onComplete(result);
                    } catch (RemoteException e) {
                        // Can't communicate with the phone process; ignore.
                    }
                }
            });
        }

        @Override
        public void eraseSubscriptions(int slotId, IEraseSubscriptionsCallback callback) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    int result = EuiccService.this.onEraseSubscriptions(slotId);
                    try {
                        callback.onComplete(result);
                    } catch (RemoteException e) {
                        // Can't communicate with the phone process; ignore.
                    }
                }
            });
        }

        @Override
        public void retainSubscriptionsForFactoryReset(int slotId,
                IRetainSubscriptionsForFactoryResetCallback callback) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    int result = EuiccService.this.onRetainSubscriptionsForFactoryReset(slotId);
                    try {
                        callback.onComplete(result);
                    } catch (RemoteException e) {
                        // Can't communicate with the phone process; ignore.
                    }
                }
            });
        }
    }
}
