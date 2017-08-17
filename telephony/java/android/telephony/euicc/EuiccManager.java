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

import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.telephony.euicc.IEuiccController;

/**
 * EuiccManager is the application interface to eUICCs, or eSIMs/embedded SIMs.
 *
 * <p>You do not instantiate this class directly; instead, you retrieve an instance through
 * {@link Context#getSystemService(String)} and {@link Context#EUICC_SERVICE}.
 *
 * <p>See {@link #isEnabled} before attempting to use these APIs.
 *
 * TODO(b/35851809): Make this public.
 * @hide
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
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS =
            "android.telephony.euicc.action.MANAGE_EMBEDDED_SUBSCRIPTIONS";

    /**
     * Intent action to provision an embedded subscription.
     *
     * <p>May be called during device provisioning to launch a screen to perform embedded SIM
     * provisioning, e.g. if no physical SIM is present and the user elects to configure their
     * embedded SIM.
     *
     * <p>The activity will immediately finish with {@link android.app.Activity#RESULT_CANCELED} if
     * {@link #isEnabled} is false or if the device is already provisioned.
     *
     * TODO(b/35851809): Make this a SystemApi.
     */
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
     * Key for an extra set on {@link PendingIntent} result callbacks providing a detailed result
     * code.
     *
     * <p>This code is an implementation detail of the embedded subscription manager and is only
     * intended for logging or debugging purposes.
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DETAILED_CODE";

    /**
     * Key for an extra set on {@link #getDownloadableSubscriptionMetadata} PendingIntent result
     * callbacks providing the downloadable subscription metadata.
     * @hide
     *
     * TODO(b/35851809): Make this a SystemApi.
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION";

    /**
     * Key for an extra set on {@link #getDefaultDownloadableSubscriptionList} PendingIntent result
     * callbacks providing the list of available downloadable subscriptions.
     * @hide
     *
     * TODO(b/35851809): Make this a SystemApi.
     */
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
     * whether the user choses to use eUICC to set up network in SUW.
     * @hide
     */
    public static final String EXTRA_FORCE_PROVISION =
            "android.telephony.euicc.extra.FORCE_PROVISION";

    /**
     * Optional meta-data attribute for a carrier app providing an icon to use to represent the
     * carrier. If not provided, the app's launcher icon will be used as a fallback.
     */
    public static final String META_DATA_CARRIER_ICON = "android.telephony.euicc.carriericon";

    private final Context mContext;
    private final IEuiccController mController;

    /** @hide */
    public EuiccManager(Context context) {
        mContext = context;
        mController = IEuiccController.Stub.asInterface(ServiceManager.getService("econtroller"));
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
        return mController != null;
    }

    /**
     * Returns the EID identifying the eUICC hardware.
     *
     * <p>Requires that the calling app has carrier privileges on the active subscription on the
     * eUICC.
     *
     * @return the EID. May be null if {@link #isEnabled()} is false or the eUICC is not ready.
     */
    @Nullable
    public String getEid() {
        if (!isEnabled()) {
            return null;
        }
        try {
            return mController.getEid();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Attempt to download the given {@link DownloadableSubscription}.
     *
     * <p>Requires the {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission,
     * or the calling app must be authorized to manage both the currently-active subscription and
     * the subscription to be downloaded according to the subscription metadata. Without the former,
     * an {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR} will be returned in the callback
     * intent to prompt the user to accept the download.
     *
     * @param subscription the subscription to download.
     * @param switchAfterDownload if true, the profile will be activated upon successful download.
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     */
    public void downloadSubscription(DownloadableSubscription subscription,
            boolean switchAfterDownload, PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            mController.downloadSubscription(subscription, switchAfterDownload,
                    mContext.getOpPackageName(), callbackIntent);
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
     * @param resolutionIntent The original intent used to start the LUI.
     * @param resolutionExtras Resolution-specific extras depending on the result of the resolution.
     *     For example, this may indicate whether the user has consented or may include the input
     *     they provided.
     * @hide
     *
     * TODO(b/35851809): Make this a SystemApi.
     */
    public void continueOperation(Intent resolutionIntent, Bundle resolutionExtras) {
        if (!isEnabled()) {
            PendingIntent callbackIntent =
                    resolutionIntent.getParcelableExtra(
                            EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_RESOLUTION_CALLBACK_INTENT);
            if (callbackIntent != null) {
                sendUnavailableError(callbackIntent);
            }
            return;
        }
        try {
            mController.continueOperation(resolutionIntent, resolutionExtras);
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
     *
     * TODO(b/35851809): Make this a SystemApi.
     */
    public void getDownloadableSubscriptionMetadata(
            DownloadableSubscription subscription, PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            mController.getDownloadableSubscriptionMetadata(
                    subscription, mContext.getOpPackageName(), callbackIntent);
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
     *
     * TODO(b/35851809): Make this a SystemApi.
     */
    public void getDefaultDownloadableSubscriptionList(PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            mController.getDefaultDownloadableSubscriptionList(
                    mContext.getOpPackageName(), callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns information about the eUICC chip/device.
     *
     * @return the {@link EuiccInfo}. May be null if {@link #isEnabled()} is false or the eUICC is
     *     not ready.
     */
    @Nullable
    public EuiccInfo getEuiccInfo() {
        if (!isEnabled()) {
            return null;
        }
        try {
            return mController.getEuiccInfo();
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
     * {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @param subscriptionId the ID of the subscription to delete.
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     */
    public void deleteSubscription(int subscriptionId, PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            mController.deleteSubscription(
                    subscriptionId, mContext.getOpPackageName(), callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Switch to (enable) the given subscription.
     *
     * <p>Requires the {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission,
     * or the calling app must be authorized to manage both the currently-active subscription and
     * the subscription to be enabled according to the subscription metadata. Without the former,
     * an {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR} will be returned in the callback
     * intent to prompt the user to accept the download.
     *
     * @param subscriptionId the ID of the subscription to enable. May be
     *     {@link android.telephony.SubscriptionManager#INVALID_SUBSCRIPTION_ID} to deactivate the
     *     current profile without activating another profile to replace it.
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     */
    public void switchToSubscription(int subscriptionId, PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            mController.switchToSubscription(
                    subscriptionId, mContext.getOpPackageName(), callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Update the nickname for the given subscription.
     *
     * <p>Requires that the calling app has the
     * {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission. This is for
     * internal system use only.
     *
     * @param subscriptionId the ID of the subscription to update.
     * @param nickname the new nickname to apply.
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     * @hide
     */
    public void updateSubscriptionNickname(
            int subscriptionId, String nickname, PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            mController.updateSubscriptionNickname(subscriptionId, nickname, callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Erase all subscriptions and reset the eUICC.
     *
     * <p>Requires that the calling app has the
     * {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission. This is for
     * internal system use only.
     *
     * @param callbackIntent a PendingIntent to launch when the operation completes.
     * @hide
     */
    public void eraseSubscriptions(PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            mController.eraseSubscriptions(callbackIntent);
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
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            mController.retainSubscriptionsForFactoryReset(callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static void sendUnavailableError(PendingIntent callbackIntent) {
        try {
            callbackIntent.send(EMBEDDED_SUBSCRIPTION_RESULT_ERROR);
        } catch (PendingIntent.CanceledException e) {
            // Caller canceled the callback; do nothing.
        }
    }
}
