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
import android.annotation.SystemApi;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
     * Result code for an operation indicating that a generic error occurred.
     *
     * <p>Note that in the future, other result codes may be returned indicating more specific
     * errors. Thus, the caller should check for {@link #EMBEDDED_SUBSCRIPTION_RESULT_OK} or
     * {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR} to determine if the operation
     * succeeded or failed with a user-resolvable error, and assume the operation failed for any
     * other result, rather than checking for this specific value.
     */
    public static final int EMBEDDED_SUBSCRIPTION_RESULT_GENERIC_ERROR = 2;

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
     * The key for an extra set on {@link #getDownloadableSubscriptionMetadata} PendingIntent result
     * callbacks providing the downloadable subscription metadata.
     * @hide
     *
     * TODO(b/35851809): Make this a SystemApi.
     */
    public static final String EXTRA_EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION";

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
     * <p>Requires the calling app to be authorized to manage both the currently-active subscription
     * and the subscription to be downloaded according to the subscription metadata. Without the
     * former, a {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR} will be returned in the
     * callback intent to prompt the user to accept the download.
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
     * @param activity the calling activity (which should be in the foreground).
     * @param requestCode an application-specific request code which will be provided to
     *     {@link Activity#onActivityResult} upon completion. Note that the operation may still be
     *     in progress when the resolution activity completes; it is not fully finished until the
     *     callback intent is triggered.
     * @param resultIntent the Intent provided to the initial callback intent which failed with
     *     {@link #EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR}.
     * @param callbackIntent a PendingIntent to launch when the operation completes. This is
     *     trigered upon completion of the original operation that required user resolution.
     */
    public void startResolutionActivity(Activity activity, int requestCode, Intent resultIntent,
            PendingIntent callbackIntent) {
        // TODO(b/33075886): Implement this API.
        throw new UnsupportedOperationException("Not implemented");
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
    @SystemApi
    public void getDownloadableSubscriptionMetadata(
            DownloadableSubscription subscription, PendingIntent callbackIntent) {
        if (!isEnabled()) {
            sendUnavailableError(callbackIntent);
            return;
        }
        try {
            mController.getDownloadableSubscriptionMetadata(subscription, callbackIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static void sendUnavailableError(PendingIntent callbackIntent) {
        try {
            callbackIntent.send(EMBEDDED_SUBSCRIPTION_RESULT_GENERIC_ERROR);
        } catch (PendingIntent.CanceledException e) {
            // Caller canceled the callback; do nothing.
        }
    }
}
