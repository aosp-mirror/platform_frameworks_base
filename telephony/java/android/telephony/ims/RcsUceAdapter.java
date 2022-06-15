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
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.ims.aidl.IImsRcsController;
import android.telephony.ims.aidl.IRcsUceControllerCallback;
import android.telephony.ims.aidl.IRcsUcePublishStateCallback;
import android.telephony.ims.feature.RcsFeature;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    @SystemApi
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
    @SystemApi
    public static final int ERROR_GENERIC_FAILURE = 1;

    /**
     * The carrier network does not have UCE support enabled for this subscriber.
     * @hide
     */
    @SystemApi
    public static final int ERROR_NOT_ENABLED = 2;

    /**
     * The data network that the device is connected to does not support UCE currently (e.g. it is
     * 1x only currently).
     * @hide
     */
    @SystemApi
    public static final int ERROR_NOT_AVAILABLE = 3;

    /**
     * The network has responded with SIP 403 error and a reason "User not registered."
     * @hide
     */
    @SystemApi
    public static final int ERROR_NOT_REGISTERED = 4;

    /**
     * The network has responded to this request with a SIP 403 error and reason "not authorized for
     * presence" for this subscriber.
     * @hide
     */
    @SystemApi
    public static final int ERROR_NOT_AUTHORIZED = 5;

    /**
     * The network has responded to this request with a SIP 403 error and no reason.
     * @hide
     */
    @SystemApi
    public static final int ERROR_FORBIDDEN = 6;

    /**
     * The contact URI requested is not provisioned for voice or it is not known as an IMS
     * subscriber to the carrier network.
     * @hide
     */
    @SystemApi
    public static final int ERROR_NOT_FOUND = 7;

    /**
     * The capabilities request contained too many URIs for the carrier network to handle. Retry
     * with a lower number of contact numbers. The number varies per carrier.
     * @hide
     */
    @SystemApi
    // TODO: Try to integrate this into the API so that the service will split based on carrier.
    public static final int ERROR_REQUEST_TOO_LARGE = 8;

    /**
     * The network did not respond to the capabilities request before the request timed out.
     * @hide
     */
    @SystemApi
    public static final int ERROR_REQUEST_TIMEOUT = 9;

    /**
     * The request failed due to the service having insufficient memory.
     * @hide
     */
    @SystemApi
    public static final int ERROR_INSUFFICIENT_MEMORY = 10;

    /**
     * The network was lost while trying to complete the request.
     * @hide
     */
    @SystemApi
    public static final int ERROR_LOST_NETWORK = 11;

    /**
     * The network is temporarily unavailable or busy. Retries should only be done after the retry
     * time returned in {@link CapabilitiesCallback#onError} has elapsed.
     * @hide
     */
    @SystemApi
    public static final int ERROR_SERVER_UNAVAILABLE = 12;

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
     * A capability update has been requested but the reason is unknown.
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_UPDATE_TRIGGER_UNKNOWN = 0;

    /**
     * A capability update has been requested due to the Entity Tag (ETag) expiring.
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_UPDATE_TRIGGER_ETAG_EXPIRED = 1;

    /**
     * A capability update has been requested due to moving to LTE with VoPS disabled.
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_LTE_VOPS_DISABLED = 2;

    /**
     * A capability update has been requested due to moving to LTE with VoPS enabled.
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_LTE_VOPS_ENABLED = 3;

    /**
     * A capability update has been requested due to moving to eHRPD.
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_EHRPD = 4;

    /**
     * A capability update has been requested due to moving to HSPA+.
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_HSPAPLUS = 5;

    /**
     * A capability update has been requested due to moving to 3G.
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_3G = 6;

    /**
     * A capability update has been requested due to moving to 2G.
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_2G = 7;

    /**
     * A capability update has been requested due to moving to WLAN
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_WLAN = 8;

    /**
     * A capability update has been requested due to moving to IWLAN
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_IWLAN = 9;

    /**
     * A capability update has been requested due to moving to 5G NR with VoPS disabled.
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_NR5G_VOPS_DISABLED = 10;

    /**
     * A capability update has been requested due to moving to 5G NR with VoPS enabled.
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_UPDATE_TRIGGER_MOVE_TO_NR5G_VOPS_ENABLED = 11;

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "ERROR_", value = {
            CAPABILITY_UPDATE_TRIGGER_UNKNOWN,
            CAPABILITY_UPDATE_TRIGGER_ETAG_EXPIRED,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_LTE_VOPS_DISABLED,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_LTE_VOPS_ENABLED,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_EHRPD,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_HSPAPLUS,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_3G,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_2G,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_WLAN,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_IWLAN,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_NR5G_VOPS_DISABLED,
            CAPABILITY_UPDATE_TRIGGER_MOVE_TO_NR5G_VOPS_ENABLED
    })
    public @interface StackPublishTriggerType {}

    /**
     * The last publish has resulted in a "200 OK" response or the device is using SIP OPTIONS for
     * UCE.
     * @hide
     */
    @SystemApi
    public static final int PUBLISH_STATE_OK = 1;

    /**
     * The hasn't published its capabilities since boot or hasn't gotten any publish response yet.
     * @hide
     */
    @SystemApi
    public static final int PUBLISH_STATE_NOT_PUBLISHED = 2;

    /**
     * The device has tried to publish its capabilities, which has resulted in an error. This error
     * is related to the fact that the device is not provisioned for voice.
     * @hide
     */
    @SystemApi
    public static final int PUBLISH_STATE_VOICE_PROVISION_ERROR = 3;

    /**
     * The device has tried to publish its capabilities, which has resulted in an error. This error
     * is related to the fact that the device is not RCS or UCE provisioned.
     * @hide
     */
    @SystemApi
    public static final int PUBLISH_STATE_RCS_PROVISION_ERROR = 4;

    /**
     * The last publish resulted in a "408 Request Timeout" response.
     * @hide
     */
    @SystemApi
    public static final int PUBLISH_STATE_REQUEST_TIMEOUT = 5;

    /**
     * The last publish resulted in another unknown error, such as SIP 503 - "Service Unavailable"
     * or SIP 423 - "Interval too short".
     * <p>
     * Device shall retry with exponential back-off.
     * @hide
     */
    @SystemApi
    public static final int PUBLISH_STATE_OTHER_ERROR = 6;

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "PUBLISH_STATE_", value = {
            PUBLISH_STATE_OK,
            PUBLISH_STATE_NOT_PUBLISHED,
            PUBLISH_STATE_VOICE_PROVISION_ERROR,
            PUBLISH_STATE_RCS_PROVISION_ERROR,
            PUBLISH_STATE_REQUEST_TIMEOUT,
            PUBLISH_STATE_OTHER_ERROR
    })
    public @interface PublishState {}

    /**
     * An application can use {@link #addOnPublishStateChangedListener} to register a
     * {@link OnPublishStateChangedListener ), which will notify the user when the publish state to
     * the network changes.
     * @hide
     */
    @SystemApi
    public interface OnPublishStateChangedListener {
        /**
         * Notifies the callback when the publish state has changed.
         * @param publishState The latest update to the publish state.
         */
        void onPublishStateChange(@PublishState int publishState);
    }

    /**
     * An application can use {@link #addOnPublishStateChangedListener} to register a
     * {@link OnPublishStateChangedListener ), which will notify the user when the publish state to
     * the network changes.
     * @hide
     */
    public static class PublishStateCallbackAdapter {

        private static class PublishStateBinder extends IRcsUcePublishStateCallback.Stub {
            private final OnPublishStateChangedListener mPublishStateChangeListener;
            private final Executor mExecutor;

            PublishStateBinder(Executor executor, OnPublishStateChangedListener listener) {
                mExecutor = executor;
                mPublishStateChangeListener = listener;
            }

            @Override
            public void onPublishStateChanged(int publishState) {
                if (mPublishStateChangeListener == null) return;

                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() ->
                            mPublishStateChangeListener.onPublishStateChange(publishState));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }
        }

        private final PublishStateBinder mBinder;

        public PublishStateCallbackAdapter(@NonNull Executor executor,
                @NonNull OnPublishStateChangedListener listener) {
            mBinder = new PublishStateBinder(executor, listener);
        }

        /**@hide*/
        public final IRcsUcePublishStateCallback getBinder() {
            return mBinder;
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
     * @see #requestCapabilities(Collection, Executor, CapabilitiesCallback)
     * @hide
     */
    @SystemApi
    public interface CapabilitiesCallback {

        /**
         * Notify this application that the pending capability request has returned successfully
         * for one or more of the requested contacts.
         * @param contactCapabilities List of capabilities associated with each contact requested.
         */
        void onCapabilitiesReceived(@NonNull List<RcsContactUceCapability> contactCapabilities);

        /**
         * The pending request has completed successfully due to all requested contacts information
         * being delivered. The callback {@link #onCapabilitiesReceived(List)}
         * for each contacts is required to be called before {@link #onComplete} is called.
         */
        void onComplete();

        /**
         * The pending request has resulted in an error and may need to be retried, depending on the
         * error code.
         * @param errorCode The reason for the framework being unable to process the request.
         * @param retryIntervalMillis The time in milliseconds the requesting application should
         * wait before retrying, if non-zero.
         */
        void onError(@ErrorCode int errorCode, long retryIntervalMillis);
    }

    private final Context mContext;
    private final int mSubId;
    private final Map<OnPublishStateChangedListener, PublishStateCallbackAdapter>
            mPublishStateCallbacks;

    /**
     * Not to be instantiated directly, use {@link ImsRcsManager#getUceAdapter()} to instantiate
     * this manager class.
     * @hide
     */
    RcsUceAdapter(Context context, int subId) {
        mContext = context;
        mSubId = subId;
        mPublishStateCallbacks = new HashMap<>();
    }

    /**
     * Request the RCS capabilities for one or more contacts using RCS User Capability Exchange.
     * <p>
     * This API will first check a local cache for the requested numbers and return the cached
     * RCS capabilities of each number if the cache exists and is not stale. If the cache for a
     * number is stale or there is no cached information about the requested number, the device will
     * then perform a query to the carrier's network to request the RCS capabilities of the
     * requested numbers.
     * <p>
     * Depending on the number of requests being sent, this API may throttled internally as the
     * operations are queued to be executed by the carrier's network.
     * <p>
     * Be sure to check the availability of this feature using
     * {@link ImsRcsManager#isAvailable(int, int)} and ensuring
     * {@link RcsFeature.RcsImsCapabilities#CAPABILITY_TYPE_OPTIONS_UCE} or
     * {@link RcsFeature.RcsImsCapabilities#CAPABILITY_TYPE_PRESENCE_UCE} is enabled or else
     * this operation will fail with {@link #ERROR_NOT_AVAILABLE} or {@link #ERROR_NOT_ENABLED}.
     *
     * @param contactNumbers A list of numbers that the capabilities are being requested for.
     * @param executor The executor that will be used when the request is completed and the
     *         {@link CapabilitiesCallback} is called.
     * @param c A one-time callback for when the request for capabilities completes or there is an
     *         error processing the request.
     * @throws ImsException if the subscription associated with this instance of
     * {@link RcsUceAdapter} is valid, but the ImsService associated with the subscription is not
     * available. This can happen if the ImsService has crashed, for example, or if the subscription
     * becomes inactive. See {@link ImsException#getCode()} for more information on the error codes.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {Manifest.permission.ACCESS_RCS_USER_CAPABILITY_EXCHANGE,
            Manifest.permission.READ_CONTACTS})
    public void requestCapabilities(@NonNull Collection<Uri> contactNumbers,
            @NonNull @CallbackExecutor Executor executor,
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
                    executor.execute(() -> c.onCapabilitiesReceived(contactCapabilities));
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
                    mContext.getAttributionTag(), new ArrayList(contactNumbers), internalCallback);
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.toString(), e.errorCode);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#requestCapabilities", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Request the RCS capabilities for a phone number using User Capability Exchange.
     * <p>
     * Unlike {@link #requestCapabilities(Collection, Executor, CapabilitiesCallback)}, which caches
     * the result received from the network for a certain amount of time and uses that cached result
     * for subsequent requests for RCS capabilities of the same phone number, this API will always
     * request the RCS capabilities of a contact from the carrier's network.
     * <p>
     * Depending on the number of requests, this API may throttled internally as the operations are
     * queued to be executed by the carrier's network.
     * <p>
     * Be sure to check the availability of this feature using
     * {@link ImsRcsManager#isAvailable(int, int)} and ensuring
     * {@link RcsFeature.RcsImsCapabilities#CAPABILITY_TYPE_OPTIONS_UCE} or
     * {@link RcsFeature.RcsImsCapabilities#CAPABILITY_TYPE_PRESENCE_UCE} is
     * enabled or else this operation will fail with
     * {@link #ERROR_NOT_AVAILABLE} or {@link #ERROR_NOT_ENABLED}.
     *
     * @param contactNumber The contact of the capabilities is being requested for.
     * @param executor The executor that will be used when the request is completed and the
     * {@link CapabilitiesCallback} is called.
     * @param c A one-time callback for when the request for capabilities completes or there is
     * an error processing the request.
     * @throws ImsException if the subscription associated with this instance of
     * {@link RcsUceAdapter} is valid, but the ImsService associated with the subscription is not
     * available. This can happen if the ImsService has crashed, for example, or if the subscription
     * becomes inactive. See {@link ImsException#getCode()} for more information on the error codes.
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {Manifest.permission.ACCESS_RCS_USER_CAPABILITY_EXCHANGE,
            Manifest.permission.READ_CONTACTS})
    public void requestAvailability(@NonNull Uri contactNumber,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull CapabilitiesCallback c) throws ImsException {
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
            Log.e(TAG, "requestAvailability: IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        IRcsUceControllerCallback internalCallback = new IRcsUceControllerCallback.Stub() {
            @Override
            public void onCapabilitiesReceived(List<RcsContactUceCapability> contactCapabilities) {
                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> c.onCapabilitiesReceived(contactCapabilities));
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
            imsRcsController.requestAvailability(mSubId, mContext.getOpPackageName(),
                    mContext.getAttributionTag(), contactNumber, internalCallback);
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.toString(), e.errorCode);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#requestAvailability", e);
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
    @SystemApi
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
     * Registers a {@link OnPublishStateChangedListener} with the system, which will provide publish
     * state updates for the subscription specified in {@link ImsManager@getRcsManager(subid)}.
     * <p>
     * Use {@link SubscriptionManager.OnSubscriptionsChangedListener} to listen to subscription
     * changed events and call
     * {@link #removeOnPublishStateChangedListener(OnPublishStateChangedListener)} to clean up.
     * <p>
     * The registered {@link OnPublishStateChangedListener} will also receive a callback when it is
     * registered with the current publish state.
     *
     * @param executor The executor the listener callback events should be run on.
     * @param listener The {@link OnPublishStateChangedListener} to be added.
     * @throws ImsException if the subscription associated with this callback is valid, but
     * the {@link ImsService} associated with the subscription is not available. This can happen if
     * the service crashed, for example. See {@link ImsException#getCode()} for a more detailed
     * reason.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void addOnPublishStateChangedListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull OnPublishStateChangedListener listener) throws ImsException {
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }
        if (listener == null) {
            throw new IllegalArgumentException(
                    "Must include a non-null OnPublishStateChangedListener.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "addOnPublishStateChangedListener : IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        PublishStateCallbackAdapter stateCallback = addPublishStateCallback(executor, listener);
        try {
            imsRcsController.registerUcePublishStateCallback(mSubId, stateCallback.getBinder());
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.getMessage(), e.errorCode);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#registerUcePublishStateCallback", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Removes an existing {@link OnPublishStateChangedListener}.
     * <p>
     * When the subscription associated with this callback is removed
     * (SIM removed, ESIM swap,etc...), this callback will automatically be removed. If this method
     * is called for an inactive subscription, it will result in a no-op.
     *
     * @param listener The callback to be unregistered.
     * @throws ImsException if the subscription associated with this callback is valid, but
     * the {@link ImsService} associated with the subscription is not available. This can happen if
     * the service crashed, for example. See {@link ImsException#getCode()} for a more detailed
     * reason.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void removeOnPublishStateChangedListener(
            @NonNull OnPublishStateChangedListener listener) throws ImsException {
        if (listener == null) {
            throw new IllegalArgumentException(
                    "Must include a non-null OnPublishStateChangedListener.");
        }
        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "removeOnPublishStateChangedListener: IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        PublishStateCallbackAdapter callback = removePublishStateCallback(listener);
        if (callback == null) {
            return;
        }

        try {
            imsRcsController.unregisterUcePublishStateCallback(mSubId, callback.getBinder());
        } catch (android.os.ServiceSpecificException e) {
            throw new ImsException(e.getMessage(), e.errorCode);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#unregisterUcePublishStateCallback", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * The setting for whether or not the user has opted in to the automatic refresh of the RCS
     * capabilities associated with the contacts in the user's contact address book. By default,
     * this setting is disabled and must be enabled after the user has seen the opt-in dialog shown
     * by {@link ImsRcsManager#ACTION_SHOW_CAPABILITY_DISCOVERY_OPT_IN}.
     * <p>
     * If this feature is enabled, the device will periodically share the phone numbers of all of
     * the contacts in the user's address book with the carrier to refresh the RCS capabilities
     * cache associated with those contacts as the local cache becomes stale.
     * <p>
     * This setting will only enable this feature if
     * {@link CarrierConfigManager.Ims#KEY_RCS_BULK_CAPABILITY_EXCHANGE_BOOL} is also enabled.
     * <p>
     * Note: This setting does not affect whether or not the device publishes its service
     * capabilities if the subscription supports presence publication.
     *
     * @return true if the user has opted in for automatic refresh of the RCS capabilities of their
     * contacts, false otherwise.
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
     * Change the user’s setting for whether or not the user has opted in to the automatic
     * refresh of the RCS capabilities associated with the contacts in the user's contact address
     * book. By default, this setting is disabled and must be enabled using this method after the
     * user has seen the opt-in dialog shown by
     * {@link ImsRcsManager#ACTION_SHOW_CAPABILITY_DISCOVERY_OPT_IN}.
     * <p>
     * If an application wishes to request that the user enable this feature, they must launch an
     * Activity using the Intent {@link ImsRcsManager#ACTION_SHOW_CAPABILITY_DISCOVERY_OPT_IN},
     * which will ask the user if they wish to enable this feature. This setting must only be
     * enabled after the user has opted-in to this feature.
     * <p>
     * This must not affect the
     * {@link #requestCapabilities(Collection, Executor, CapabilitiesCallback)} or
     * {@link #requestAvailability(Uri, Executor, CapabilitiesCallback)} API,
     * as those APIs are still required for per-contact RCS capability queries of phone numbers
     * required for operations such as placing a Video Telephony call or starting an RCS chat
     * session.
     * <p>
     * This setting will only enable this feature if
     * {@link CarrierConfigManager.Ims#KEY_RCS_BULK_CAPABILITY_EXCHANGE_BOOL} is also enabled.
     * <p>
     * Note: This setting does not affect whether or not the device publishes its service
     * capabilities if the subscription supports presence publication.
     *
     * @param isEnabled true if the user has opted in for automatic refresh of the RCS capabilities
     *                  of their contacts, or false if they have chosen to opt-out. By default this
     *                  setting is disabled.
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

    /**
     * Add the {@link OnPublishStateChangedListener} to collection for tracking.
     * @param executor The executor that will be used when the publish state is changed and the
     * {@link OnPublishStateChangedListener} is called.
     * @param listener The {@link OnPublishStateChangedListener} to call the publish state changed.
     * @return The {@link PublishStateCallbackAdapter} to wrapper the
     * {@link OnPublishStateChangedListener}
     */
    private PublishStateCallbackAdapter addPublishStateCallback(@NonNull Executor executor,
            @NonNull OnPublishStateChangedListener listener) {
        PublishStateCallbackAdapter adapter = new PublishStateCallbackAdapter(executor, listener);
        synchronized (mPublishStateCallbacks) {
            mPublishStateCallbacks.put(listener, adapter);
        }
        return adapter;
    }

    /**
     * Remove the existing {@link OnPublishStateChangedListener}.
     * @param listener The {@link OnPublishStateChangedListener} to remove from the collection.
     * @return The wrapper class {@link PublishStateCallbackAdapter} associated with the
     * {@link OnPublishStateChangedListener}.
     */
    private PublishStateCallbackAdapter removePublishStateCallback(
            @NonNull OnPublishStateChangedListener listener) {
        synchronized (mPublishStateCallbacks) {
            return mPublishStateCallbacks.remove(listener);
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
