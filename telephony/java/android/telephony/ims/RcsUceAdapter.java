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

package android.telephony.ims;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.ims.aidl.IImsRcsController;
import android.telephony.ims.aidl.IRcsUceControllerCallback;
import android.telephony.ims.aidl.IRcsUcePublishStateCallback;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Manages RCS User Capability Exchange for the subscription specified.
 *
 * @see ImsRcsManager#getUceAdapter() for information on creating an instance of this class.
 */
public class RcsUceAdapter {
    private static final String TAG = "RcsUceAdapter";

    /**
     * This carrier supports User Capability Exchange as, defined by the framework using
     * SIP OPTIONS. If set, the RcsFeature should support capability exchange. If not set, this
     * RcsFeature should not publish capabilities or service capability requests.
     * @hide
     */
    public static final int CAPABILITY_TYPE_OPTIONS_UCE = 1 << 0;

    /**
     * This carrier supports User Capability Exchange as, defined by the framework using a
     * presence server. If set, the RcsFeature should support capability exchange. If not set, this
     * RcsFeature should not publish capabilities or service capability requests.
     * @hide
     */
    public static final int CAPABILITY_TYPE_PRESENCE_UCE = 1 << 1;

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "CAPABILITY_TYPE_", value = {
            CAPABILITY_TYPE_OPTIONS_UCE,
            CAPABILITY_TYPE_PRESENCE_UCE
    })
    public @interface RcsImsCapabilityFlag {}

    /**
     * An unknown error has caused the request to fail.
     * @hide
     */
    public static final int ERROR_GENERIC_FAILURE = 1;

    /**
     * The carrier network does not have UCE support enabled for this subscriber.
     * @hide
     */
    public static final int ERROR_NOT_ENABLED = 2;

    /**
     * The data network that the device is connected to does not support UCE currently (e.g. it is
     * 1x only currently).
     * @hide
     */
    public static final int ERROR_NOT_AVAILABLE = 3;

    /**
     * The network has responded with SIP 403 error and a reason "User not registered."
     * @hide
     */
    public static final int ERROR_NOT_REGISTERED = 4;

    /**
     * The network has responded to this request with a SIP 403 error and reason "not authorized for
     * presence" for this subscriber.
     * @hide
     */
    public static final int ERROR_NOT_AUTHORIZED = 5;

    /**
     * The network has responded to this request with a SIP 403 error and no reason.
     * @hide
     */
    public static final int ERROR_FORBIDDEN = 6;

    /**
     * The contact URI requested is not provisioned for VoLTE or it is not known as an IMS
     * subscriber to the carrier network.
     * @hide
     */
    public static final int ERROR_NOT_FOUND = 7;

    /**
     * The capabilities request contained too many URIs for the carrier network to handle. Retry
     * with a lower number of contact numbers. The number varies per carrier.
     * @hide
     */
    // TODO: Try to integrate this into the API so that the service will split based on carrier.
    public static final int ERROR_REQUEST_TOO_LARGE = 8;

    /**
     * The network did not respond to the capabilities request before the request timed out.
     * @hide
     */
    public static final int ERROR_REQUEST_TIMEOUT = 10;

    /**
     * The request failed due to the service having insufficient memory.
     * @hide
     */
    public static final int ERROR_INSUFFICIENT_MEMORY = 11;

    /**
     * The network was lost while trying to complete the request.
     * @hide
     */
    public static final int ERROR_LOST_NETWORK = 12;

    /**
     * The network is temporarily unavailable or busy. Retries should only be done after the retry
     * time returned in {@link CapabilitiesCallback#onError} has elapsed.
     * @hide
     */
    public static final int ERROR_SERVER_UNAVAILABLE = 13;

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "ERROR_", value = {
            ERROR_GENERIC_FAILURE,
            ERROR_NOT_ENABLED,
            ERROR_NOT_AVAILABLE,
            ERROR_NOT_REGISTERED,
            ERROR_NOT_AUTHORIZED,
            ERROR_FORBIDDEN,
            ERROR_NOT_FOUND,
            ERROR_REQUEST_TOO_LARGE,
            ERROR_REQUEST_TIMEOUT,
            ERROR_INSUFFICIENT_MEMORY,
            ERROR_LOST_NETWORK,
            ERROR_SERVER_UNAVAILABLE
    })
    public @interface ErrorCode {}

    /**
     * A capability update has been requested due to the Entity Tag (ETag) expiring.
     * @hide
     */
    public static final int CAPABILITY_UPDATE_TRIGGER_ETAG_EXPIRED = 0;
    /**
     * A capability update has been requested due to moving to LTE with VoPS disabled.
     * @hide
     */
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_LTE_VOPS_DISABLED = 1;
    /**
     * A capability update has been requested due to moving to LTE with VoPS enabled.
     * @hide
     */
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_LTE_VOPS_ENABLED = 2;
    /**
     * A capability update has been requested due to moving to eHRPD.
     * @hide
     */
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_EHRPD = 3;
    /**
     * A capability update has been requested due to moving to HSPA+.
     * @hide
     */
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_HSPAPLUS = 4;
    /**
     * A capability update has been requested due to moving to 3G.
     * @hide
     */
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_3G = 5;
    /**
     * A capability update has been requested due to moving to 2G.
     * @hide
     */
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_2G = 6;
    /**
     * A capability update has been requested due to moving to WLAN
     * @hide
     */
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_WLAN = 7;
    /**
     * A capability update has been requested due to moving to IWLAN
     * @hide
     */
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_IWLAN = 8;
    /**
     * A capability update has been requested but the reason is unknown.
     * @hide
     */
    public static final int CAPABILITY_UPDATE_TRIGGER_UNKNOWN = 9;
    /**
     * A capability update has been requested due to moving to 5G NR with VoPS disabled.
     * @hide
     */
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_NR5G_VOPS_DISABLED = 10;
    /**
     * A capability update has been requested due to moving to 5G NR with VoPS enabled.
     * @hide
     */
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_NR5G_VOPS_ENABLED = 11;

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "ERROR_", value = {
            CAPABILITY_UPDATE_TRIGGER_ETAG_EXPIRED,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_LTE_VOPS_DISABLED,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_LTE_VOPS_ENABLED,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_EHRPD,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_HSPAPLUS,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_3G,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_2G,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_WLAN,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_IWLAN,
            CAPABILITY_UPDATE_TRIGGER_UNKNOWN,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_NR5G_VOPS_DISABLED,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_NR5G_VOPS_ENABLED
    })
    public @interface StackPublishTriggerType {}

    /**
     * The last publish has resulted in a "200 OK" response or the device is using SIP OPTIONS for
     * UCE.
     * @hide
     */
    public static final int PUBLISH_STATE_OK = 1;

    /**
     * The hasn't published its capabilities since boot or hasn't gotten any publish response yet.
     * @hide
     */
    public static final int PUBLISH_STATE_NOT_PUBLISHED = 2;

    /**
     * The device has tried to publish its capabilities, which has resulted in an error. This error
     * is related to the fact that the device is not VoLTE provisioned.
     * @hide
     */
    public static final int PUBLISH_STATE_VOLTE_PROVISION_ERROR = 3;

    /**
     * The device has tried to publish its capabilities, which has resulted in an error. This error
     * is related to the fact that the device is not RCS or UCE provisioned.
     * @hide
     */
    public static final int PUBLISH_STATE_RCS_PROVISION_ERROR = 4;

    /**
     * The last publish resulted in a "408 Request Timeout" response.
     * @hide
     */
    public static final int PUBLISH_STATE_REQUEST_TIMEOUT = 5;

    /**
     * The last publish resulted in another unknown error, such as SIP 503 - "Service Unavailable"
     * or SIP 423 - "Interval too short".
     * <p>
     * Device shall retry with exponential back-off.
     * @hide
     */
    public static final int PUBLISH_STATE_OTHER_ERROR = 6;

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "PUBLISH_STATE_", value = {
            PUBLISH_STATE_OK,
            PUBLISH_STATE_NOT_PUBLISHED,
            PUBLISH_STATE_VOLTE_PROVISION_ERROR,
            PUBLISH_STATE_RCS_PROVISION_ERROR,
            PUBLISH_STATE_REQUEST_TIMEOUT,
            PUBLISH_STATE_OTHER_ERROR
    })
    public @interface PublishState {}

    /**
     * An application can use {@link #registerPublishStateCallback} to register a
     * {@link PublishStateCallback), which will notify the user when the publish state to the
     * network changes.
     * @hide
     */
    public static class PublishStateCallback {

        private static class PublishStateBinder extends IRcsUcePublishStateCallback.Stub {

            private final PublishStateCallback mLocalCallback;
            private Executor mExecutor;

            PublishStateBinder(PublishStateCallback c) {
                mLocalCallback = c;
            }

            @Override
            public void onPublishStateChanged(int publishState) {
                if (mLocalCallback == null) return;

                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mLocalCallback.onChanged(publishState));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }

            private void setExecutor(Executor executor) {
                mExecutor = executor;
            }
        }

        private final PublishStateBinder mBinder = new PublishStateBinder(this);

        /**@hide*/
        public final IRcsUcePublishStateCallback getBinder() {
            return mBinder;
        }

        private void setExecutor(Executor executor) {
            mBinder.setExecutor(executor);
        }

        /**
         * Notifies the callback when the publish state has changed.
         * @param publishState The latest update to the publish state.
         */
        public void onChanged(@PublishState int publishState) {
        }
    }

    /**
     * A callback for the response to a UCE request. The method
     * {@link CapabilitiesCallback#onCapabilitiesReceived} will be called zero or more times as the
     * capabilities are received for each requested contact.
     * <p>
     * This request will take a varying amount of time depending on if the contacts requested are
     * cached or if it requires a network query. The timeout time of these requests can vary
     * depending on the network, however in poor cases it could take up to a minute for a request
     * to timeout. In that time only a subset of capabilities may have been retrieved.
     * <p>
     * After {@link CapabilitiesCallback#onComplete} or {@link CapabilitiesCallback#onError} has
     * been called, the reference to this callback will be discarded on the service side.
     * @see #requestCapabilities(Executor, List, CapabilitiesCallback)
     * @hide
     */
    public interface CapabilitiesCallback {

        /**
         * Notify this application that the pending capability request has returned successfully
         * for one or more of the requested contacts.
         * @param contactCapabilities List of capabilities associated with each contact requested.
         */
        void onCapabilitiesReceived(@NonNull List<RcsContactUceCapability> contactCapabilities);

        /**
         * The pending request has completed successfully due to all requested contacts information
         * being delivered.
         */
        void onComplete();

        /**
         * The pending request has resulted in an error and may need to be retried, depending on the
         * error code.
         * @param errorCode The reason for the framework being unable to process the request.
         * @param retryAfterMilliseconds The time in milliseconds the requesting application should
         * wait before retrying, if non-zero.
         */
        void onError(@ErrorCode int errorCode, long retryAfterMilliseconds);
    }

    private final Context mContext;
    private final int mSubId;

    /**
     * Not to be instantiated directly, use {@link ImsRcsManager#getUceAdapter()} to instantiate
     * this manager class.
     * @hide
     */
    RcsUceAdapter(Context context, int subId) {
        mContext = context;
        mSubId = subId;
    }

    /**
     * Request the User Capability Exchange capabilities for one or more contacts.
     * <p>
     * This will return the cached capabilities of the contact and will not perform a capability
     * poll on the network unless there are contacts being queried with stale information.
     * <p>
     * Be sure to check the availability of this feature using
     * {@link ImsRcsManager#isAvailable(int)} and ensuring
     * {@link RcsFeature.RcsImsCapabilities#CAPABILITY_TYPE_OPTIONS_UCE} or
     * {@link RcsFeature.RcsImsCapabilities#CAPABILITY_TYPE_PRESENCE_UCE} is enabled or else
     * this operation will fail with {@link #ERROR_NOT_AVAILABLE} or {@link #ERROR_NOT_ENABLED}.
     *
     * @param executor The executor that will be used when the request is completed and the
     *         {@link CapabilitiesCallback} is called.
     * @param contactNumbers A list of numbers that the capabilities are being requested for.
     * @param c A one-time callback for when the request for capabilities completes or there is an
     *         error processing the request.
     * @throws ImsException if the subscription associated with this instance of
     * {@link RcsUceAdapter} is valid, but the ImsService associated with the subscription is not
     * available. This can happen if the ImsService has crashed, for example, or if the subscription
     * becomes inactive. See {@link ImsException#getCode()} for more information on the error codes.
     * @hide
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void requestCapabilities(@NonNull @CallbackExecutor Executor executor,
            @NonNull List<Uri> contactNumbers,
            @NonNull CapabilitiesCallback c) throws ImsException {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null CapabilitiesCallback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }
        if (contactNumbers == null) {
            throw new IllegalArgumentException("Must include non-null contact number list.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "requestCapabilities: IImsRcsController is null");
            throw new ImsException("Can not find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        IRcsUceControllerCallback internalCallback = new IRcsUceControllerCallback.Stub() {
            @Override
            public void onCapabilitiesReceived(List<RcsContactUceCapability> contactCapabilities) {
                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() ->
                            c.onCapabilitiesReceived(contactCapabilities));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }
            @Override
            public void onComplete() {
                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> c.onComplete());
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }
            @Override
            public void onError(int errorCode, long retryAfterMilliseconds) {
                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> c.onError(errorCode, retryAfterMilliseconds));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }
        };

        try {
            imsRcsController.requestCapabilities(mSubId, mContext.getOpPackageName(),
                    mContext.getAttributionTag(), contactNumbers, internalCallback);
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.toString(), e.errorCode);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#requestCapabilities", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Ignore the device cache and perform a capability discovery for one contact, also called
     * "availability fetch."
     * <p>
     * This will always perform a query to the network as long as requests are over the carrier
     * availability fetch throttling threshold. If too many network requests are sent too quickly,
     * #ERROR_TOO_MANY_REQUESTS will be returned.
     *
     * <p>
     * Be sure to check the availability of this feature using
     * {@link ImsRcsManager#isAvailable(int)} and ensuring
     * {@link RcsFeature.RcsImsCapabilities#CAPABILITY_TYPE_OPTIONS_UCE} or
     * {@link RcsFeature.RcsImsCapabilities#CAPABILITY_TYPE_PRESENCE_UCE} is
     * enabled or else this operation will fail with
     * {@link #ERROR_NOT_AVAILABLE} or {@link #ERROR_NOT_ENABLED}.
     *
     * @param contactNumber The contact of the capabilities is being requested for.
     * @param c A one-time callback for when the request for capabilities completes or there is
     * an error processing the request.
     * @hide
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void requestNetworkAvailability(@NonNull @CallbackExecutor Executor executor,
            @NonNull Uri contactNumber, @NonNull CapabilitiesCallback c) throws ImsException {
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }
        if (contactNumber == null) {
            throw new IllegalArgumentException("Must include non-null contact number.");
        }
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null CapabilitiesCallback.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "requestNetworkAvailability: IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        IRcsUceControllerCallback internalCallback = new IRcsUceControllerCallback.Stub() {
            @Override
            public void onCapabilitiesReceived(List<RcsContactUceCapability> contactCapabilities) {
                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() ->
                            c.onCapabilitiesReceived(contactCapabilities));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }
            @Override
            public void onComplete() {
                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> c.onComplete());
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }
            @Override
            public void onError(int errorCode, long retryAfterMilliseconds) {
                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> c.onError(errorCode, retryAfterMilliseconds));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }
        };

        try {
            imsRcsController.requestNetworkAvailability(mSubId, mContext.getOpPackageName(),
                    mContext.getAttributionTag(), contactNumber, internalCallback);
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.toString(), e.errorCode);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#requestNetworkAvailability", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Gets the last publish result from the UCE service if the device is using an RCS presence
     * server.
     * @return The last publish result from the UCE service. If the device is using SIP OPTIONS,
     * this method will return {@link #PUBLISH_STATE_OK} as well.
     * @throws ImsException if the subscription associated with this instance of
     * {@link RcsUceAdapter} is valid, but the ImsService associated with the subscription is not
     * available. This can happen if the ImsService has crashed, for example, or if the subscription
     * becomes inactive. See {@link ImsException#getCode()} for more information on the error codes.
     * @hide
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @PublishState int getUcePublishState() throws ImsException {
        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "getUcePublishState: IImsRcsController is null");
            throw new ImsException("Can not find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        try {
            return imsRcsController.getUcePublishState(mSubId);
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.getMessage(), e.errorCode);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#getUcePublishState", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Registers a {@link PublishStateCallback} with the system, which will provide publish state
     * updates for the subscription specified in {@link ImsManager@getRcsManager(subid)}.
     * <p>
     * Use {@link SubscriptionManager.OnSubscriptionsChangedListener} to listen to subscription
     * changed events and call {@link #unregisterPublishStateCallback} to clean up.
     * <p>
     * The registered {@link PublishStateCallback} will also receive a callback when it is
     * registered with the current publish state.
     *
     * @param executor The executor the listener callback events should be run on.
     * @param c The {@link PublishStateCallback} to be added.
     * @throws ImsException if the subscription associated with this callback is valid, but
     * the {@link ImsService} associated with the subscription is not available. This can happen if
     * the service crashed, for example. See {@link ImsException#getCode()} for a more detailed
     * reason.
     * @hide
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void registerPublishStateCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull PublishStateCallback c) throws ImsException {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null PublishStateCallback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "registerPublishStateCallback : IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        c.setExecutor(executor);
        try {
            imsRcsController.registerUcePublishStateCallback(mSubId, c.getBinder());
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.getMessage(), e.errorCode);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#registerUcePublishStateCallback", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Removes an existing {@link PublishStateCallback}.
     * <p>
     * When the subscription associated with this callback is removed
     * (SIM removed, ESIM swap,etc...), this callback will automatically be removed. If this method
     * is called for an inactive subscription, it will result in a no-op.
     *
     * @param c The callback to be unregistered.
     * @throws ImsException if the subscription associated with this callback is valid, but
     * the {@link ImsService} associated with the subscription is not available. This can happen if
     * the service crashed, for example. See {@link ImsException#getCode()} for a more detailed
     * reason.
     * @hide
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void unregisterPublishStateCallback(@NonNull PublishStateCallback c)
            throws ImsException {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null PublishStateCallback.");
        }
        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "unregisterPublishStateCallback: IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        try {
            imsRcsController.unregisterUcePublishStateCallback(mSubId, c.getBinder());
        } catch (android.os.ServiceSpecificException e) {
            throw new ImsException(e.getMessage(), e.errorCode);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#unregisterUcePublishStateCallback", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * The user’s setting for whether or not User Capability Exchange (UCE) is enabled for the
     * associated subscription.
     * <p>
     * Note: This setting does not affect whether or not the device publishes its service
     * capabilities if the subscription supports presence publication.
     *
     * @return true if the user’s setting for UCE is enabled, false otherwise.
     * @throws ImsException if the subscription associated with this instance of
     * {@link RcsUceAdapter} is valid, but the ImsService associated with the subscription is not
     * available. This can happen if the ImsService has crashed, for example, or if the subscription
     * becomes inactive. See {@link ImsException#getCode()} for more information on the error codes.
     */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    public boolean isUceSettingEnabled() throws ImsException {
        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "isUceSettingEnabled: IImsRcsController is null");
            throw new ImsException("Can not find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
        try {
            // Telephony.SimInfo#IMS_RCS_UCE_ENABLED can also be used to listen to changes to this.
            return imsRcsController.isUceSettingEnabled(mSubId, mContext.getOpPackageName(),
                    mContext.getAttributionTag());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#isUceSettingEnabled", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Change the user’s setting for whether or not UCE is enabled for the associated subscription.
     * <p>
     * If an application Requires UCE, they will launch an Activity using the Intent
     * {@link ImsRcsManager#ACTION_SHOW_CAPABILITY_DISCOVERY_OPT_IN}, which will ask the user if
     * they wish to enable this feature. This setting should only be enabled after the user has
     * opted-in to capability exchange.
     * <p>
     * Note: This setting does not affect whether or not the device publishes its service
     * capabilities if the subscription supports presence publication.
     *
     * @param isEnabled the user's setting for whether or not they wish for User
     *         Capability Exchange to be enabled.
     * @throws ImsException if the subscription associated with this instance of
     * {@link RcsUceAdapter} is valid, but the ImsService associated with the subscription is not
     * available. This can happen if the ImsService has crashed, for example, or if the subscription
     * becomes inactive. See {@link ImsException#getCode()} for more information on the error codes.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setUceSettingEnabled(boolean isEnabled) throws ImsException {
        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "setUceSettingEnabled: IImsRcsController is null");
            throw new ImsException("Can not find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        try {
            imsRcsController.setUceSettingEnabled(mSubId, isEnabled);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#setUceSettingEnabled", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    private IImsRcsController getIImsRcsController() {
        IBinder binder = TelephonyFrameworkInitializer
                .getTelephonyServiceManager()
                .getTelephonyImsServiceRegisterer()
                .get();
        return IImsRcsController.Stub.asInterface(binder);
    }
}
