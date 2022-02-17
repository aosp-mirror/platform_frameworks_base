/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.telephony.ims;

import android.annotation.NonNull;
import android.annotation.RequiresFeature;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.BinderCacheManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.ims.aidl.IImsRcsController;

import com.android.internal.telephony.ITelephony;

/**
 * Provides access to information about Telephony IMS services on the device.
 */
@SystemService(Context.TELEPHONY_IMS_SERVICE)
@RequiresFeature(PackageManager.FEATURE_TELEPHONY_IMS)
public class ImsManager {

    /**
     * <p>Broadcast Action: Indicates that a previously allowed IMS operation was rejected by the
     * network due to the network returning a "forbidden" response. This may be due to a
     * provisioning change from the network.
     * May include the {@link SubscriptionManager#EXTRA_SUBSCRIPTION_INDEX} extra to also specify
     * which subscription the operation was rejected for.
     * <p class="note">
     * Carrier applications may listen to this broadcast to be notified of possible IMS provisioning
     * issues.
     * @hide
     */
    // Moved from TelephonyIntents, need to keep backwards compatibility with OEM apps that have
    // this value hard-coded in BroadcastReceiver.
    @SuppressLint("ActionValue")
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_FORBIDDEN_NO_SERVICE_AUTHORIZATION =
            "com.android.internal.intent.action.ACTION_FORBIDDEN_NO_SERVICE_AUTHORIZATION";

    /**
     * An intent action indicating that IMS registration for WiFi calling has resulted in an error.
     * Contains error information that should be displayed to the user.
     * <p>
     * This intent will contain the following extra key/value pairs:
     * {@link #EXTRA_WFC_REGISTRATION_FAILURE_TITLE}
     * and {@link #EXTRA_WFC_REGISTRATION_FAILURE_MESSAGE}, which contain carrier specific
     * error information that should be displayed to the user.
     * <p>
     * Usage: This intent is sent as an ordered broadcast. If the settings application is going
     * to show the error information specified to the user, it should respond to
     * {@link android.content.BroadcastReceiver#setResultCode(int)} with
     * {@link android.app.Activity#RESULT_CANCELED}, which will signal to the framework that the
     * event was handled. If the framework does not receive a response to the ordered broadcast,
     * it will then show a notification to the user indicating that there was a registration
     * failure.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_WFC_IMS_REGISTRATION_ERROR =
            "android.telephony.ims.action.WFC_IMS_REGISTRATION_ERROR";

    /**
     * An extra key corresponding to a {@link CharSequence} value which contains the carrier
     * specific title to be displayed as part of the message shown to the user when there is an
     * error registering for WiFi calling.
     */
    public static final String EXTRA_WFC_REGISTRATION_FAILURE_TITLE =
            "android.telephony.ims.extra.WFC_REGISTRATION_FAILURE_TITLE";

    /**
     * An extra key corresponding to a {@link CharSequence} value which contains the carrier
     * specific message to  be displayed as part of the message shown to the user when there is an
     * error registering for WiFi calling.
     */
    public static final String EXTRA_WFC_REGISTRATION_FAILURE_MESSAGE =
            "android.telephony.ims.extra.WFC_REGISTRATION_FAILURE_MESSAGE";

    // Cache Telephony Binder interfaces, one cache per process.
    private static final BinderCacheManager<ITelephony> sTelephonyCache =
            new BinderCacheManager<>(ImsManager::getITelephonyInterface);
    private static final BinderCacheManager<IImsRcsController> sRcsCache =
            new BinderCacheManager<>(ImsManager::getIImsRcsControllerInterface);

    private final Context mContext;

    /**
     * Use {@link Context#getSystemService(String)} to get an instance of this class.
     * @hide
     */
    public ImsManager(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Create an instance of ImsRcsManager for the subscription id specified.
     *
     * @param subscriptionId The ID of the subscription that this ImsRcsManager will use.
     * @throws IllegalArgumentException if the subscription is invalid.
     * @return a ImsRcsManager instance with the specific subscription ID.
     */
    @NonNull
    public ImsRcsManager getImsRcsManager(int subscriptionId) {
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            throw new IllegalArgumentException("Invalid subscription ID: " + subscriptionId);
        }

        return new ImsRcsManager(mContext, subscriptionId, sRcsCache, sTelephonyCache);
    }

    /**
     * Create an instance of ImsMmTelManager for the subscription id specified.
     *
     * @param subscriptionId The ID of the subscription that this ImsMmTelManager will use.
     * @throws IllegalArgumentException if the subscription is invalid.
     * @return a ImsMmTelManager instance with the specific subscription ID.
     */
    @NonNull
    public ImsMmTelManager getImsMmTelManager(int subscriptionId) {
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            throw new IllegalArgumentException("Invalid subscription ID: " + subscriptionId);
        }

        return new ImsMmTelManager(mContext, subscriptionId, sTelephonyCache);
    }

    /**
     * Create an instance of {@link SipDelegateManager} for the subscription id specified.
     * <p>
     * Allows an IMS application to forward SIP traffic through the device's IMS service,
     * which is used for cellular carriers that require the device to share a single IMS
     * registration for both MMTEL and RCS features.
     * @param subscriptionId The ID of the subscription that this {@link SipDelegateManager} will
     *                       be bound to.
     * @throws IllegalArgumentException if the subscription is invalid.
     * @return a {@link SipDelegateManager} instance for the specified subscription ID.
     * @hide
     */
    @SystemApi
    @NonNull
    public SipDelegateManager getSipDelegateManager(int subscriptionId) {
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            throw new IllegalArgumentException("Invalid subscription ID: " + subscriptionId);
        }

        return new SipDelegateManager(mContext, subscriptionId, sRcsCache, sTelephonyCache);
    }


    /**
     * Create an instance of {@link ProvisioningManager} for the subscription id specified.
     * <p>
     * Provides a ProvisioningManager instance to carrier apps to update carrier provisioning
     * information, as well as provides a callback so that apps can listen for changes
     * in MMTEL/RCS provisioning
     * @param subscriptionId The ID of the subscription that this ProvisioningManager will use.
     * @throws IllegalArgumentException if the subscription is invalid.
     * @return a ProvisioningManager instance with the specific subscription ID.
     */
    @NonNull
    public ProvisioningManager getProvisioningManager(int subscriptionId) {
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            throw new IllegalArgumentException("Invalid subscription ID: " + subscriptionId);
        }

        return new ProvisioningManager(subscriptionId);
    }

    private static IImsRcsController getIImsRcsControllerInterface() {
        return IImsRcsController.Stub.asInterface(
                TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .getTelephonyImsServiceRegisterer()
                        .get());
    }

    private static ITelephony getITelephonyInterface() {
        return ITelephony.Stub.asInterface(
                TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .getTelephonyServiceRegisterer()
                        .get());
    }
}
