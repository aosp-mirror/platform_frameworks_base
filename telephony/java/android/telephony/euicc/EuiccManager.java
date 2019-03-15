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
package android.telephony.euicc;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.euicc.IEuiccController;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * EuiccManager is the application interface to eUICCs, or eSIMs/embedded SIMs.
 *
 * <p>You do not instantiate this class directly; instead, you retrieve an instance through
 * {@link Context#getSystemService(String)} and {@link Context#EUICC_SERVICE}. This instance will be
 * created using the default eUICC.
 *
 * <p>On a device with multiple eUICCs, you may want to create multiple EuiccManagers. To do this
 * you can call {@link #createForCardId}.
 *
 * <p>See {@link #isEnabled} before attempting to use these APIs.
 */
public class EuiccManager {

    /**
     * Intent action to launch the embedded SIM (eUICC) management settings screen.
     *
     * <p>This screen shows a list of embedded profiles and offers the user the ability to switch
     * between them, download new profiles, and delete unused profiles.
     *
     * <p>The activity will immediately finish with {@link android.app.Activity#RESULT_CANCELED} if
     * {@link #isEnabled} is false.
     *
     * This is ued by non-LPA app to bring up LUI.
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS =
            "android.telephony.euicc.action.MANAGE_EMBEDDED_SUBSCRIPTIONS";

    /**
     * Broadcast Action: The eUICC OTA status is changed.
     * <p class="note">
     * Requires the {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * <p class="note">This is a protected intent that can only be sent
     * by the system.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public static final String ACTION_OTA_STATUS_CHANGED =
            "android.telephony.euicc.action.OTA_STATUS_CHANGED";

    /**
     * Broadcast Action: The action sent to carrier app so it knows the carrier setup is not
     * completed.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NOTIFY_CARRIER_SETUP_INCOMPLETE =
            "android.telephony.euicc.action.NOTIFY_CARRIER_SETUP_INCOMPLETE";

    /**
     * Intent action to provision an embedded subscription.
     *
     * <p>May be called during device provisioning to launch a screen to perform embedded SIM
     * provisioning, e.g. if no physical SIM is present and the user elects to configure their
     * embedded SIM.
     *
     * <p>The activity will immediately finish with {@link android.app.Activity#RESULT_CANCELED} if
     * {@link #isEnabled} is false.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISION_EMBEDDED_SUBSCRIPTION =
            "android.telephony.euicc.action.PROVISION_EMBEDDED_SUBSCRIPTION";

    /**
     * Intent action to handle a resolvable error.
     * @hide
     */
    public static final String ACTION_RESOLVE_ERROR =
            "android.telephony.euicc.action.RESOLVE_ERROR";

    /**
     * Intent action sent by system apps (such as the Settings app) to the Telephony framework to
     * enable or disable a subscription. Must be accompanied with {@link #EXTRA_SUBSCRIPTION_ID} and
     * {@link #EXTRA_ENABLE_SUBSCRIPTION}.
     *
     * <p>Unlike {@link #switchToSubscription(int, PendingIntent)}, using this action allows the
     * underlying eUICC service (i.e. the LPA app) to control the UI experience during this
     * operation. The action is received by the Telephony framework, which in turn selects and
     * launches an appropriate LPA activity to present UI to the user. For example, the activity may
     * show a confirmation dialog, a progress dialog, or an error dialog when necessary.
     *
     * <p>The launched activity will immediately finish with
     * {@link android.app.Activity#RESULT_CANCELED} if {@link #isEnabled} is false.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_TOGGLE_SUBSCRIPTION_PRIVILEGED =
            "android.telephony.euicc.action.TOGGLE_SUBSCRIPTION_PRIVILEGED";

    /**
     * Intent action sent by system apps (such as the Settings app) to the Telephony framework to
     * delete a subscription. Must be accompanied with {@link #EXTRA_SUBSCRIPTION_ID}.
     *
     * <p>Unlike {@link #deleteSubscription(int, PendingIntent)}, using this action allows the
     * underlying eUICC service (i.e. the LPA app) to control the UI experience during this
     * operation. The action is received by the Telephony framework, which in turn selects and
     * launches an appropriate LPA activity to present UI to the user. For example, the activity may
     * show a confirmation dialog, a progress dialog, or an error dialog when necessary.
     *
     * <p>The launched activity will immediately finish with
     * {@link android.app.Activity#RESULT_CANCELED} if {@link #isEnabled} is false.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_DELETE_SUBSCRIPTION_PRIVILEGED =
            "android.telephony.euicc.action.DELETE_SUBSCRIPTION_PRIVILEGED";

    /**
     * Intent action sent by system apps (such as the Settings app) to the Telephony framework to
     * rename a subscription. Must be accompanied with {@link #EXTRA_SUBSCRIPTION_ID} and
     * {@link #EXTRA_SUBSCRIPTION_NICKNAME}.
     *
     * <p>Unlike {@link #updateSubscriptionNickname(int, String, PendingIntent)}, using this action
     * allows the the underlying eUICC service (i.e. the LPA app) to control the UI experience
     * during this operation. The action is received by the Telephony framework, which in turn
     * selects and launches an appropriate LPA activity to present UI to the user. For example, the
     * activity may show a confirmation dialog, a progress dialog, or an error dialog when
     * necessary.
     *
     * <p>The launched activity will immediately finish with
     * {@link android.app.Activity#RESULT_CANCELED} if {@link #isEnabled} is false.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_RENAME_SUBSCRIPTION_PRIVILEGED =
            "android.telephony.euicc.action.RENAME_SUBSCRIPTION_PRIVILEGED";

    /**
     * Result code for an operation indicating that the operation succeeded.
     */
    public static final int EMBEDDED_SUBSCRIPTION_RESULT_OK = 0;

    /**
     * Result code for an operation indicating that the user must take some action before the
     * operation can continue.
     *
     * @see #startResolutionActivity
     */
    public static final int EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR = 1;

    /**
     * Result code for an operation indicating that an unresolvable error occurred.
     *
     * {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE} will be populated with a detailed error
     * code for logging/debugging purposes only.
     */
    public static final int EMBEDDED_SUBSCRIPTION_RESULT_ERROR = 2;

    /**
     * Key for an extra set on the {@link #ACTION_PROVISION_EMBEDDED_SUBSCRIPTION} intent for which
     * kind of activation flow will be evolved. (see {@code EUICC_ACTIVATION_})
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ACTIVATION_TYPE =
            "android.telephony.euicc.extra.ACTIVATION_TYPE";

    /**
     * Key for an extra set on {@link PendingIntent} result callbacks providing a detailed result
     * code.
     *
     * <p>This code is an implementation detail of the embedded subscription manager and is only
     * intended for logging or debugging purposes.
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DETAILED_CODE";

    /**
     * Key for an extra set on {@code #getDownloadableSubscriptionMetadata} PendingIntent result
     * callbacks providing the downloadable subscription metadata.
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION";

    /**
     * Key for an extra set on {@link #getDefaultDownloadableSubscriptionList} PendingIntent result
     * callbacks providing the list of available downloadable subscriptions.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTIONS =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTIONS";

    /**
     * Key for an extra set on {@link PendingIntent} result callbacks providing the resolution
     * pending intent for {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR}s.
     * @hide
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_INTENT =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_RESOLUTION_INTENT";

    /**
     * Key for an extra set on the {@link #EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_INTENT} intent
     * containing the EuiccService action to launch for resolution.
     * @hide
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_ACTION =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_RESOLUTION_ACTION";

    /**
     * Key for an extra set on the {@link #EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_INTENT} intent
     * providing the callback to execute after resolution is completed.
     * @hide
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_CALLBACK_INTENT =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_RESOLUTION_CALLBACK_INTENT";

    /**
     * Key for an extra set on the {@link #ACTION_PROVISION_EMBEDDED_SUBSCRIPTION} intent for
     * whether eSIM provisioning flow is forced to be started or not. If this extra hasn't been
     * set, eSIM provisioning flow may be skipped and the corresponding carrier's app will be
     * notified. Otherwise, eSIM provisioning flow will be started when
     * {@link #ACTION_PROVISION_EMBEDDED_SUBSCRIPTION} has been received.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_FORCE_PROVISION =
            "android.telephony.euicc.extra.FORCE_PROVISION";

    /**
     * Key for an extra set on privileged actions {@link #ACTION_TOGGLE_SUBSCRIPTION_PRIVILEGED},
     * {@link #ACTION_DELETE_SUBSCRIPTION_PRIVILEGED}, and
     * {@link #ACTION_RENAME_SUBSCRIPTION_PRIVILEGED} providing the ID of the targeted subscription.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_SUBSCRIPTION_ID =
            "android.telephony.euicc.extra.SUBSCRIPTION_ID";

    /**
     * Key for an extra set on {@link #ACTION_TOGGLE_SUBSCRIPTION_PRIVILEGED} providing a boolean
     * value of whether to enable or disable the targeted subscription.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ENABLE_SUBSCRIPTION =
            "android.telephony.euicc.extra.ENABLE_SUBSCRIPTION";

    /**
     * Key for an extra set on {@link #ACTION_RENAME_SUBSCRIPTION_PRIVILEGED} providing a new
     * nickname for the targeted subscription.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_SUBSCRIPTION_NICKNAME =
            "android.telephony.euicc.extra.SUBSCRIPTION_NICKNAME";

    /**
     * Optional meta-data attribute for a carrier app providing an icon to use to represent the
     * carrier. If not provided, the app's launcher icon will be used as a fallback.
     */
    public static final String META_DATA_CARRIER_ICON = "android.telephony.euicc.carriericon";

    /**
     * Euicc activation type which will be included in {@link #EXTRA_ACTIVATION_TYPE} and used to
     * decide which kind of activation flow should be lauched.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"EUICC_ACTIVATION_"}, value = {
            EUICC_ACTIVATION_TYPE_DEFAULT,
            EUICC_ACTIVATION_TYPE_BACKUP,
            EUICC_ACTIVATION_TYPE_TRANSFER,
            EUICC_ACTIVATION_TYPE_ACCOUNT_REQUIRED,
    })
    public @interface EuiccActivationType{}


    /**
     * The default euicc activation type which includes checking server side and downloading the
     * profile based on carrier's download configuration.
     *
     * @hide
     */
    @SystemApi
    public static final int EUICC_ACTIVATION_TYPE_DEFAULT = 1;

    /**
     * The euicc activation type used when the default download process failed. LPA will start the
     * backup flow and try to download the profile for the carrier.
     *
     * @hide
     */
    @SystemApi
    public static final int EUICC_ACTIVATION_TYPE_BACKUP = 2;

    /**
     * The activation flow of eSIM seamless transfer will be used. LPA will start normal eSIM
     * activation flow and if it's failed, the name of the carrier selected will be recorded. After
     * the future device pairing, LPA will contact this carrier to transfer it from the other device
     * to this device.
     *
     * @hide
     */
    @SystemApi
    public static final int EUICC_ACTIVATION_TYPE_TRANSFER = 3;

    /**
     * The activation flow of eSIM requiring user account will be started. This can only be used
     * when there is user account signed in. Otherwise, the flow will be failed.
     *
     * @hide
     */
    @SystemApi
    public static final int EUICC_ACTIVATION_TYPE_ACCOUNT_REQUIRED = 4;

    /**
     * Euicc OTA update status which can be got by {@link #getOtaStatus}
     * @hide
     */
    @SystemApi
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"EUICC_OTA_"}, value = {
            EUICC_OTA_IN_PROGRESS,
            EUICC_OTA_FAILED,
            EUICC_OTA_SUCCEEDED,
            EUICC_OTA_NOT_NEEDED,
            EUICC_OTA_STATUS_UNAVAILABLE

    })
    public @interface OtaStatus{}

    /**
     * An OTA is in progress. During this time, the eUICC is not available and the user may lose
     * network access.
     * @hide
     */
    @SystemApi
    public static final int EUICC_OTA_IN_PROGRESS = 1;

    /**
     * The OTA update failed.
     * @hide
     */
    @SystemApi
    public static final int EUICC_OTA_FAILED = 2;

    /**
     * The OTA update finished successfully.
     * @hide
     */
    @SystemApi
    public static final int EUICC_OTA_SUCCEEDED = 3;

    /**
     * The OTA update not needed since current eUICC OS is latest.
     * @hide
     */
    @SystemApi
    public static final int EUICC_OTA_NOT_NEEDED = 4;

    /**
     * The OTA status is unavailable since eUICC service is unavailable.
     * @hide
     */
    @SystemApi
    public static final int EUICC_OTA_STATUS_UNAVAILABLE = 5;

    private final Context mContext;
    private int mCardId;

    /** @hide */
    public EuiccManager(Context context) {
        mContext = context;
        TelephonyManager tm = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
        mCardId = tm.getCardIdForDefaultEuicc();
    }

    /** @hide */
    private EuiccManager(Context context, int cardId) {
        mContext = context;
        mCardId = cardId;
    }

    /**
     * Create a new EuiccManager object pinned to the given card ID.
     *
     * @return an EuiccManager that uses the given card ID for all calls.
     */
    @NonNull
    public EuiccManager createForCardId(int cardId) {
        return new EuiccManager(mContext, cardId);
    }

    /**
     * Whether embedded subscriptions are currently enabled.
     *
     * <p>Even on devices with the {@link PackageManager#FEATURE_TELEPHONY_EUICC} feature, embedded
     * subscriptions may be turned off, e.g. because of a carrier restriction from an inserted
     * physical SIM. Therefore, this runtime check should be used before accessing embedded
     * subscription APIs.
     *
     * @return true if embedded subscriptions are currently enabled.
     */
    public boolean isEnabled() {
        // In the future, this may reach out to IEuiccController (if non-null) to check any dynamic
        // restrictions.
        return getIEuiccController() != null;
    }

    /**
     * Returns the EID identifying the eUICC hardware.
     *
     * <p>Requires that the calling app has carrier privileges on the active subscription on the
     * current eUICC. A calling app with carrier privileges for one eUICC may not necessarily have
     * access to the EID of another eUICC.
     *
     * @return the EID. May be null if the eUICC is not ready.
     */
    @Nullable
    public String getEid() {
        if (!refreshCardIdIfUninitialized()) {
            return null;
        }
        try {
            return getIEuiccController().getEid(mCardId, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current status of eUICC OTA.
     *
     * <p>Requires the {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @return the status of eUICC OTA. If the eUICC is not ready,
     *         {@link OtaStatus#EUICC_OTA_STATUS_UNAVAILABLE} will be returned.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public int getOtaStatus() {
        if (!refreshCardIdIfUninitialized()) {
            return EUICC_OTA_STATUS_UNAVAILABLE;
        }
        try {
            return getIEuiccController().getOtaStatus(mCardId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Attempt to download the given {@link DownloadableSubscription}.
     *
     * <p>Requires the {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission,
     * or the calling app must be authorized to manage both the currently-active subscription on the
     * current eUICC and the subscription to be downloaded according to the subscription metadata.
     * Without the former, an {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR} will be
     * returned in the callback intent to prompt the user to accept the download.
     *
     * <p>On a multi-active SIM device, requires the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission, or a calling app
     * only if the targeted eUICC does not currently have an active subscription or the calling app
     * is authorized to manage the active subscription on the target eUICC, and the calling app is
     * authorized to manage any active subscription on any SIM. Without it, an
     * {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR} will be returned in the callback
     * intent to prompt the user to accept the download. The caller should also be authorized to
     * manage the subscription to be downloaded.
     *
     * @param subscription the subscription to download.
     * @param switchAfterDownload if true, the profile will be activated upon successful download.
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     */
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void downloadSubscription(DownloadableSubscription subscription,
            boolean switchAfterDownload, PendingIntent callbackIntent) {
        if (!refreshCardIdIfUninitialized()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().downloadSubscription(mCardId, subscription, switchAfterDownload,
                    mContext.getOpPackageName(), null /* resolvedBundle */, callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Start an activity to resolve a user-resolvable error.
     *
     * <p>If an operation returns {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR}, this
     * method may be called to prompt the user to resolve the issue.
     *
     * <p>This method may only be called once for a particular error.
     *
     * @param activity the calling activity (which should be in the foreground).
     * @param requestCode an application-specific request code which will be provided to
     *     {@link Activity#onActivityResult} upon completion. Note that the operation may still be
     *     in progress when the resolution activity completes; it is not fully finished until the
     *     callback intent is triggered.
     * @param resultIntent the Intent provided to the initial callback intent which failed with
     *     {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR}.
     * @param callbackIntent a PendingIntent to launch when the operation completes. This is
     *     trigered upon completion of the original operation that required user resolution.
     * @throws android.content.IntentSender.SendIntentException if called more than once.
     */
    public void startResolutionActivity(Activity activity, int requestCode, Intent resultIntent,
            PendingIntent callbackIntent) throws IntentSender.SendIntentException {
        PendingIntent resolutionIntent =
                resultIntent.getParcelableExtra(EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_INTENT);
        if (resolutionIntent == null) {
            throw new IllegalArgumentException("Invalid result intent");
        }
        Intent fillInIntent = new Intent();
        fillInIntent.putExtra(EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_CALLBACK_INTENT,
                callbackIntent);
        activity.startIntentSenderForResult(resolutionIntent.getIntentSender(), requestCode,
                fillInIntent, 0 /* flagsMask */, 0 /* flagsValues */, 0 /* extraFlags */);
    }

    /**
     * Continue an operation after the user resolves an error.
     *
     * <p>To be called by the LUI upon completion of a resolvable error flow.
     *
     * <p>Requires that the calling app has the
     * {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @param resolutionIntent The original intent used to start the LUI.
     * @param resolutionExtras Resolution-specific extras depending on the result of the resolution.
     *     For example, this may indicate whether the user has consented or may include the input
     *     they provided.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void continueOperation(Intent resolutionIntent, Bundle resolutionExtras) {
        if (!refreshCardIdIfUninitialized()) {
            PendingIntent callbackIntent =
                    resolutionIntent.getParcelableExtra(
                            EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_CALLBACK_INTENT);
            if (callbackIntent != null) {
                sendUnavailableError(callbackIntent);
            }
            return;
        }
        try {
            getIEuiccController().continueOperation(mCardId, resolutionIntent, resolutionExtras);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Fills in the metadata for a DownloadableSubscription.
     *
     * <p>May be used in cases that a DownloadableSubscription was constructed to download a
     * profile, but the metadata for the profile is unknown (e.g. we only know the activation code).
     * The callback will be triggered with an Intent with
     * {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION} set to the
     * downloadable subscription metadata upon success.
     *
     * <p>Requires that the calling app has the
     * {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission. This is for
     * internal system use only.
     *
     * @param subscription the subscription which needs metadata filled in
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void getDownloadableSubscriptionMetadata(
            DownloadableSubscription subscription, PendingIntent callbackIntent) {
        if (!refreshCardIdIfUninitialized()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().getDownloadableSubscriptionMetadata(mCardId, subscription,
                    mContext.getOpPackageName(), callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets metadata for subscription which are available for download on this device.
     *
     * <p>Subscriptions returned here may be passed to {@link #downloadSubscription}. They may have
     * been pre-assigned to this particular device, for example. The callback will be triggered with
     * an Intent with {@link #EXTRA_EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTIONS} set to the
     * list of available subscriptions upon success.
     *
     * <p>Requires that the calling app has the
     * {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission. This is for
     * internal system use only.
     *
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void getDefaultDownloadableSubscriptionList(PendingIntent callbackIntent) {
        if (!refreshCardIdIfUninitialized()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().getDefaultDownloadableSubscriptionList(mCardId,
                    mContext.getOpPackageName(), callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns information about the eUICC chip/device.
     *
     * @return the {@link EuiccInfo}. May be null if the eUICC is not ready.
     */
    @Nullable
    public EuiccInfo getEuiccInfo() {
        if (!refreshCardIdIfUninitialized()) {
            return null;
        }
        try {
            return getIEuiccController().getEuiccInfo(mCardId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes the given subscription.
     *
     * <p>If this subscription is currently active, the device will first switch away from it onto
     * an "empty" subscription.
     *
     * <p>Requires that the calling app has carrier privileges according to the metadata of the
     * profile to be deleted, or the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @param subscriptionId the ID of the subscription to delete.
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     */
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void deleteSubscription(int subscriptionId, PendingIntent callbackIntent) {
        if (!refreshCardIdIfUninitialized()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().deleteSubscription(mCardId,
                    subscriptionId, mContext.getOpPackageName(), callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Switch to (enable) the given subscription.
     *
     * <p>Requires the {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission,
     * or the calling app must be authorized to manage both the currently-active subscription and
     * the subscription to be enabled according to the subscription metadata. Without the former,
     * an {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR} will be returned in the callback
     * intent to prompt the user to accept the download.
     *
     * <p>On a multi-active SIM device, requires the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission, or a calling app
     *  only if the targeted eUICC does not currently have an active subscription or the calling app
     * is authorized to manage the active subscription on the target eUICC, and the calling app is
     * authorized to manage any active subscription on any SIM. Without it, an
     * {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR} will be returned in the callback
     * intent to prompt the user to accept the download. The caller should also be authorized to
     * manage the subscription to be enabled.
     *
     * @param subscriptionId the ID of the subscription to enable. May be
     *     {@link android.telephony.SubscriptionManager#INVALID_SUBSCRIPTION_ID} to deactivate the
     *     current profile without activating another profile to replace it. If it's a disable
     *     operation, requires the {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS}
     *     permission, or the calling app must be authorized to manage the active subscription on
     *     the target eUICC.
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     */
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void switchToSubscription(int subscriptionId, PendingIntent callbackIntent) {
        if (!refreshCardIdIfUninitialized()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().switchToSubscription(mCardId,
                    subscriptionId, mContext.getOpPackageName(), callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Update the nickname for the given subscription.
     *
     * <p>Requires that the calling app has carrier privileges according to the metadata of the
     * profile to be updated, or the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @param subscriptionId the ID of the subscription to update.
     * @param nickname the new nickname to apply.
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     */
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void updateSubscriptionNickname(
            int subscriptionId, @Nullable String nickname, @NonNull PendingIntent callbackIntent) {
        if (!refreshCardIdIfUninitialized()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().updateSubscriptionNickname(mCardId,
                    subscriptionId, nickname, mContext.getOpPackageName(), callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Erase all subscriptions and reset the eUICC.
     *
     * <p>Requires that the calling app has the
     * {@code android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void eraseSubscriptions(PendingIntent callbackIntent) {
        if (!refreshCardIdIfUninitialized()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().eraseSubscriptions(mCardId, callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Ensure that subscriptions will be retained on the next factory reset.
     *
     * <p>By default, all subscriptions on the eUICC are erased the first time a device boots (ever
     * and after factory resets). This ensures that the data is wiped after a factory reset is
     * performed via fastboot or recovery mode, as these modes do not support the necessary radio
     * communication needed to wipe the eSIM.
     *
     * <p>However, this method may be called right before a factory reset issued via settings when
     * the user elects to retain subscriptions. Doing so will mark them for retention so that they
     * are not cleared after the ensuing reset.
     *
     * <p>Requires that the calling app has the {@link android.Manifest.permission#MASTER_CLEAR}
     * permission. This is for internal system use only.
     *
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     * @hide
     */
    public void retainSubscriptionsForFactoryReset(PendingIntent callbackIntent) {
        if (!refreshCardIdIfUninitialized()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            getIEuiccController().retainSubscriptionsForFactoryReset(mCardId, callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Refreshes the cardId if its uninitialized, and returns whether we should continue the
     * operation.
     * <p>
     * Note that after a successful refresh, the mCardId may be TelephonyManager.UNSUPPORTED_CARD_ID
     * on older HALs. For backwards compatability, we continue to the LPA and let it decide which
     * card to use.
     */
    private boolean refreshCardIdIfUninitialized() {
        // Refresh mCardId if its UNINITIALIZED_CARD_ID
        if (mCardId == TelephonyManager.UNINITIALIZED_CARD_ID) {
            TelephonyManager tm = (TelephonyManager)
                    mContext.getSystemService(Context.TELEPHONY_SERVICE);
            mCardId = tm.getCardIdForDefaultEuicc();
        }
        if (mCardId == TelephonyManager.UNINITIALIZED_CARD_ID) {
            return false;
        }
        return true;
    }

    private static void sendUnavailableError(PendingIntent callbackIntent) {
        try {
            callbackIntent.send(EMBEDDED_SUBSCRIPTION_RESULT_ERROR);
        } catch (PendingIntent.CanceledException e) {
            // Caller canceled the callback; do nothing.
        }
    }

    private static IEuiccController getIEuiccController() {
        return IEuiccController.Stub.asInterface(ServiceManager.getService("econtroller"));
    }
}
