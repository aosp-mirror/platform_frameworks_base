/*
 * Copyright (c) 2020 The Android Open Source Project
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

package android.telephony.ims.stub;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;
import android.telephony.ims.ImsException;
import android.telephony.ims.aidl.ICapabilityExchangeEventListener;
import android.util.Log;
import android.util.Pair;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Base class for different types of Capability exchange.
 * @hide
 */
public class RcsCapabilityExchangeImplBase {

    private static final String LOG_TAG = "RcsCapExchangeImplBase";

    /**
     * Service is unknown.
     */
    public static final int COMMAND_CODE_SERVICE_UNKNOWN = 0;

    /**
     * The command failed with an unknown error.
     */
    public static final int COMMAND_CODE_GENERIC_FAILURE = 1;

    /**
     * Invalid parameter(s).
     */
    public static final int COMMAND_CODE_INVALID_PARAM = 2;

    /**
     * Fetch error.
     */
    public static final int COMMAND_CODE_FETCH_ERROR = 3;

    /**
     * Request timed out.
     */
    public static final int COMMAND_CODE_REQUEST_TIMEOUT = 4;

    /**
     * Failure due to insufficient memory available.
     */
    public static final int COMMAND_CODE_INSUFFICIENT_MEMORY = 5;

    /**
     * Network connection is lost.
     * @hide
     */
    public static final int COMMAND_CODE_LOST_NETWORK_CONNECTION = 6;

    /**
     * Requested feature/resource is not supported.
     * @hide
     */
    public static final int COMMAND_CODE_NOT_SUPPORTED = 7;

    /**
     * Contact or resource is not found.
     */
    public static final int COMMAND_CODE_NOT_FOUND = 8;

    /**
     * Service is not available.
     */
    public static final int COMMAND_CODE_SERVICE_UNAVAILABLE = 9;

    /**
     * Command resulted in no change in state, ignoring.
     */
    public static final int COMMAND_CODE_NO_CHANGE = 10;

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "COMMAND_CODE_", value = {
            COMMAND_CODE_SERVICE_UNKNOWN,
            COMMAND_CODE_GENERIC_FAILURE,
            COMMAND_CODE_INVALID_PARAM,
            COMMAND_CODE_FETCH_ERROR,
            COMMAND_CODE_REQUEST_TIMEOUT,
            COMMAND_CODE_INSUFFICIENT_MEMORY,
            COMMAND_CODE_LOST_NETWORK_CONNECTION,
            COMMAND_CODE_NOT_SUPPORTED,
            COMMAND_CODE_NOT_FOUND,
            COMMAND_CODE_SERVICE_UNAVAILABLE,
            COMMAND_CODE_NO_CHANGE
    })
    public @interface CommandCode {}

    /**
     * Interface used by the framework to receive the response of the publish request.
     */
    public interface PublishResponseCallback {
        /**
         * Notify the framework that the command associated with this callback has failed.
         *
         * @param code The reason why the associated command has failed.
         * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is
         * not currently connected to the framework. This can happen if the {@link RcsFeature}
         * is not {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received
         * the {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare cases
         * when the Telephony stack has crashed.
         */
        void onCommandError(@CommandCode int code) throws ImsException;


        /**
         * Provide the framework with a subsequent network response update to
         * {@link #publishCapabilities(String, PublishResponseCallback)}.
         *
         * @param code The SIP response code sent from the network for the operation
         * token specified.
         * @param reason The optional reason response from the network. If the network
         *  provided no reason with the code, the string should be empty.
         * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is
         * not currently connected to the framework. This can happen if the {@link RcsFeature}
         * is not {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received
         * the {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare cases
         * when the Telephony stack has crashed.
         */
        void onNetworkResponse(@IntRange(from = 100, to = 699) int code,
                @NonNull String reason) throws ImsException;
    }

    /**
     * Interface used by the framework to respond to OPTIONS requests.
     */
    public interface OptionsResponseCallback {
        /**
         * Notify the framework that the command associated with this callback has failed.
         *
         * @param code The reason why the associated command has failed.
         * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is
         * not currently connected to the framework. This can happen if the
         * {@link RcsFeature} is not {@link ImsFeature#STATE_READY} and the {@link RcsFeature}
         * has not received the {@link ImsFeature#onFeatureReady()} callback. This may also happen
         * in rare cases when the Telephony stack has crashed.
         */
        void onCommandError(@CommandCode int code) throws ImsException;

        /**
         * Send the response of a SIP OPTIONS capability exchange to the framework.
         * @param code The SIP response code that was sent by the network in response
         * to the request sent by {@link #sendOptionsCapabilityRequest}.
         * @param reason The optional SIP response reason sent by the network.
         * If none was sent, this should be an empty string.
         * @param theirCaps the contact's UCE capabilities associated with the
         * capability request.
         * @throws ImsException If this {@link RcsSipOptionsImplBase} instance is not
         * currently connected to the framework. This can happen if the
         * {@link RcsFeature} is not {@link ImsFeature#STATE_READY} and the
         * {@link RcsFeature} has not received the
         * {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare
         * cases when the Telephony stack has crashed.
         */
        void onNetworkResponse(int code, @NonNull String reason,
                @Nullable List<String> theirCaps) throws ImsException;
    }

    /**
     * Interface used by the framework to receive the response of the subscribe request.
     */
    public interface SubscribeResponseCallback {
        /**
         * Notify the framework that the command associated with this callback has failed.
         *
         * @param code The reason why the associated command has failed.
         * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is
         * not currently connected to the framework. This can happen if the
         * {@link RcsFeature} is not
         * {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received
         * the {@link ImsFeature#onFeatureReady()} callback. This may also happen in
         * rare cases when the Telephony stack has crashed.
         */
        void onCommandError(@CommandCode int code) throws ImsException;

        /**
         * Notify the framework of the response to the SUBSCRIBE request from
         * {@link #subscribeForCapabilities(List<Uri>, SubscribeResponseCallback)}.
         *
         * @param code The SIP response code sent from the network for the operation
         * token specified.
         * @param reason The optional reason response from the network. If the network
         *  provided no reason with the code, the string should be empty.
         * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is
         * not currently connected to the framework. This can happen if the
         * {@link RcsFeature} is not {@link ImsFeature#STATE_READY} and the
         * {@link RcsFeature} has not received the {@link ImsFeature#onFeatureReady()} callback.
         * This may also happen in rare cases when the Telephony stack has crashed.
         */
        void onNetworkResponse(@IntRange(from = 100, to = 699) int code,
                @NonNull String reason) throws ImsException;

        /**
         * Provides the framework with latest XML PIDF documents included in the
         * network response for the requested  contacts' capabilities requested by the
         * Framework  using {@link #requestCapabilities(List, int)}. This should be
         * called every time a new NOTIFY event is received with new capability
         * information.
         *
         * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is
         * not currently
         * connected to the framework. This can happen if the {@link RcsFeature} is not
         * {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received
         * the {@link ImsFeature#onFeatureReady()} callback. This may also happen in
         * rare cases when the
         * Telephony stack has crashed.
         */
        void onNotifyCapabilitiesUpdate(@NonNull List<String> pidfXmls) throws ImsException;

        /**
         * A resource in the resource list for the presence subscribe event has been terminated.
         * <p>
         * This allows the framework to know that there will not be any capability information for
         * a specific contact URI that they subscribed for.
         */
        void onResourceTerminated(
                @NonNull List<Pair<Uri, String>> uriTerminatedReason) throws ImsException;

        /**
         * The subscription associated with a previous #requestCapabilities operation
         * has been terminated. This will mostly be due to the subscription expiring,
         * but may also happen due to an error.
         * <p>
         * This allows the framework to know that there will no longer be any
         * capability updates for the requested operationToken.
         */
        void onTerminated(String reason, long retryAfterMilliseconds) throws ImsException;
    }


    private ICapabilityExchangeEventListener mListener;

    /**
     * Set the event listener to send the request to Framework.
     */
    public void setEventListener(ICapabilityExchangeEventListener listener) {
        mListener = listener;
    }

    /**
     * Get the event listener.
     */
    public ICapabilityExchangeEventListener getEventListener() {
        return mListener;
    }

    /**
     * The user capabilities of one or multiple contacts have been requested by the framework.
     * <p>
     * The response from the network to the SUBSCRIBE request must be sent back to the framework
     * using {@link #onSubscribeNetworkResponse(int, String, int)}. As NOTIFY requests come in from
     * the network, the requested contact’s capabilities should be sent back to the framework using
     * {@link #onSubscribeNotifyRequest} and {@link onSubscribeResourceTerminated}
     * should be called with the presence information for the contacts specified.
     * <p>
     * Once the subscription is terminated, {@link #onSubscriptionTerminated} must be called for
     * the framework to finish listening for NOTIFY responses.
     * @param uris A {@link List} of the {@link Uri}s that the framework is requesting the UCE
     * capabilities for.
     * @param cb The callback of the subscribe request.
     */
    public void subscribeForCapabilities(@NonNull List<Uri> uris,
            @NonNull SubscribeResponseCallback cb) {
        // Stub - to be implemented by service
        Log.w(LOG_TAG, "subscribeForCapabilities called with no implementation.");
        try {
            cb.onCommandError(COMMAND_CODE_NOT_SUPPORTED);
        } catch (ImsException e) {
            // Do not do anything, this is a stub implementation.
        }
    }

    /**
     * The capabilities of this device have been updated and should be published to the network.
     * <p>
     * If this operation succeeds, network response updates should be sent to the framework using
     * {@link #onNetworkResponse(int, String)}.
     * @param pidfXml The XML PIDF document containing the capabilities of this device to be sent
     * to the carrier’s presence server.
     * @param cb The callback of the publish request
     */
    public void publishCapabilities(@NonNull String pidfXml, @NonNull PublishResponseCallback cb) {
        // Stub - to be implemented by service
        Log.w(LOG_TAG, "publishCapabilities called with no implementation.");
        try {
            cb.onCommandError(COMMAND_CODE_NOT_SUPPORTED);
        } catch (ImsException e) {
            // Do not do anything, this is a stub implementation.
        }
    }

    /**
     * Push one's own capabilities to a remote user via the SIP OPTIONS presence exchange mechanism
     * in order to receive the capabilities of the remote user in response.
     * <p>
     * The implementer must call {@link #onNetworkResponse} to send the response of this
     * query back to the framework.
     * @param contactUri The URI of the remote user that we wish to get the capabilities of.
     * @param myCapabilities The capabilities of this device to send to the remote user.
     * @param callback The callback of this request which is sent from the remote user.
     */
    public void sendOptionsCapabilityRequest(@NonNull Uri contactUri,
            @NonNull List<String> myCapabilities, @NonNull OptionsResponseCallback callback) {
        // Stub - to be implemented by service
        Log.w(LOG_TAG, "sendOptionsCapabilityRequest called with no implementation.");
        try {
            callback.onCommandError(COMMAND_CODE_NOT_SUPPORTED);
        } catch (ImsException e) {
            // Do not do anything, this is a stub implementation.
        }
    }
}
