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
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.net.Uri;
import android.telephony.ims.ImsException;
import android.telephony.ims.RcsUceAdapter;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.RcsFeature;
import android.util.Log;
import android.util.Pair;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Extend this base class to implement RCS User Capability Exchange (UCE) for the AOSP platform
 * using the vendor ImsService.
 * <p>
 * See RCC.07 for more details on UCE as well as how UCE should be implemented.
 * @hide
 */
@SystemApi
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
     */
    public static final int COMMAND_CODE_LOST_NETWORK_CONNECTION = 6;

    /**
     * Requested feature/resource is not supported.
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
         * Notify the framework that the command associated with the
         * {@link #publishCapabilities(String, PublishResponseCallback)} has failed.
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
         * If this network response also contains a “Reason” header, then the
         * {@link #onNetworkResponse(int, String, int, String)} method should be used instead.
         *
         * @param sipCode The SIP response code sent from the network for the operation
         * token specified.
         * @param reason The optional reason response from the network. If there is a reason header
         * included in the response, that should take precedence over the reason provided in the
         * status line. If the network provided no reason with the sip code, the string should be
         * empty.
         * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is
         * not currently connected to the framework. This can happen if the {@link RcsFeature}
         * is not {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received
         * the {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare cases
         * when the Telephony stack has crashed.
         */
        void onNetworkResponse(@IntRange(from = 100, to = 699) int sipCode,
                @NonNull String reason) throws ImsException;

        /**
         * Provide the framework with a subsequent network response update to
         * {@link #publishCapabilities(String, PublishResponseCallback)} that also
         * includes a reason provided in the “reason” header. See RFC3326 for more
         * information.
         *
         * @param sipCode The SIP response code sent from the network.
         * @param reasonPhrase The optional reason response from the network. If the
         * network provided no reason with the sip code, the string should be empty.
         * @param reasonHeaderCause The “cause” parameter of the “reason” header
         * included in the SIP message.
         * @param reasonHeaderText The “text” parameter of the “reason” header
         * included in the SIP message.
         * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is
         * not currently connected to the framework. This can happen if the
         * {@link RcsFeature} is not
         * {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received
         * the {@link ImsFeature#onFeatureReady()} callback. This may also happen in
         * rare cases when the Telephony stack has crashed.
         */
        void onNetworkResponse(@IntRange(from = 100, to = 699) int sipCode,
                @NonNull String reasonPhrase,
                @IntRange(from = 100, to = 699) int reasonHeaderCause,
                @NonNull String reasonHeaderText) throws ImsException;
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
         * @param sipCode The SIP response code that was sent by the network in response
         * to the request sent by {@link #sendOptionsCapabilityRequest}.
         * @param reason The optional SIP response reason sent by the network.
         * If none was sent, this should be an empty string.
         * @param theirCaps the contact's UCE capabilities associated with the
         * capability request.
         * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is not
         * currently connected to the framework. This can happen if the
         * {@link RcsFeature} is not {@link ImsFeature#STATE_READY} and the
         * {@link RcsFeature} has not received the
         * {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare
         * cases when the Telephony stack has crashed.
         */
        void onNetworkResponse(int sipCode, @NonNull String reason,
                @NonNull List<String> theirCaps) throws ImsException;
    }

    /**
     * Interface used by the framework to receive the response of the subscribe request.
     */
    public interface SubscribeResponseCallback {
        /**
         * Notify the framework that the command associated with this callback has failed.
         * <p>
         * Must only be called when there was an error generating a SUBSCRIBE request due to an
         * IMS stack error. This is a terminating event, so no other callback event will be
         * expected after this callback.
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
         * {@link #subscribeForCapabilities(Collection, SubscribeResponseCallback)}.
         * <p>
         * If the carrier network responds to the SUBSCRIBE request with a 2XX response, then the
         * framework will expect the IMS stack to call {@link #onNotifyCapabilitiesUpdate},
         * {@link #onResourceTerminated}, and {@link #onTerminated} as required for the
         * subsequent NOTIFY responses to the subscription.
         *
         * If this network response also contains a “Reason” header, then the
         * {@link #onNetworkResponse(int, String, int, String)} method should be used instead.
         *
         * @param sipCode The SIP response code sent from the network for the operation
         * token specified.
         * @param reason The optional reason response from the network. If the network
         *  provided no reason with the sip code, the string should be empty.
         * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is
         * not currently connected to the framework. This can happen if the
         * {@link RcsFeature} is not {@link ImsFeature#STATE_READY} and the
         * {@link RcsFeature} has not received the {@link ImsFeature#onFeatureReady()} callback.
         * This may also happen in rare cases when the Telephony stack has crashed.
         */
        void onNetworkResponse(@IntRange(from = 100, to = 699) int sipCode,
                @NonNull String reason) throws ImsException;

        /**
         * Notify the framework  of the response to the SUBSCRIBE request from
         * {@link #subscribeForCapabilities(Collection, SubscribeResponseCallback)} that also
         * includes a reason provided in the “reason” header. See RFC3326 for more
         * information.
         *
         * @param sipCode The SIP response code sent from the network,
         * @param reasonPhrase The optional reason response from the network. If the
         * network provided no reason with the sip code, the string should be empty.
         * @param reasonHeaderCause The “cause” parameter of the “reason” header
         * included in the SIP message.
         * @param reasonHeaderText The “text” parameter of the “reason” header
         * included in the SIP message.
         * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is
         * not currently connected to the framework. This can happen if the
         * {@link RcsFeature} is not
         * {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received
         * the {@link ImsFeature#onFeatureReady()} callback. This may also happen in
         * rare cases when the Telephony stack has crashed.
         */
        void onNetworkResponse(@IntRange(from = 100, to = 699) int sipCode,
                @NonNull String reasonPhrase,
                @IntRange(from = 100, to = 699) int reasonHeaderCause,
                @NonNull String reasonHeaderText) throws ImsException;

        /**
         * Notify the framework of the latest XML PIDF documents included in the network response
         * for the requested contacts' capabilities requested by the Framework using
         * {@link RcsUceAdapter#requestCapabilities(List, Executor,
         * RcsUceAdapter.CapabilitiesCallback)}.
         * <p>
         * The expected format for the PIDF XML is defined in RFC3861. Each XML document must be a
         * "application/pidf+xml" object and start with a root <presence> element. For NOTIFY
         * responses that contain RLMI information and potentially multiple PIDF XMLs, each
         * PIDF XML should be separated and added as a separate item in the List. This should be
         * called every time a new NOTIFY event is received with new capability information.
         *
         * @param pidfXmls The list of the PIDF XML data for the contact URIs that it subscribed
         * for.
         * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is
         * not currently connected to the framework.
         * This can happen if the {@link RcsFeature} is not {@link ImsFeature#STATE_READY} and the
         * {@link RcsFeature} {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not
         * received the {@link ImsFeature#onFeatureReady()} callback. This may also happen in
         * rare cases when the Telephony stack has crashed.
         */
        void onNotifyCapabilitiesUpdate(@NonNull List<String> pidfXmls) throws ImsException;

        /**
         * Notify the framework that a resource in the RLMI XML contained in the NOTIFY response
         * for the ongoing SUBSCRIBE dialog has been terminated.
         * <p>
         * This will be used to notify the framework that a contact URI that the IMS stack has
         * subscribed to on the Resource List Server has been terminated as well as the reason why.
         * Usually this means that there will not be any capability information for the contact URI
         * that they subscribed for. See RFC 4662 for more information.
         *
         * @param uriTerminatedReason The contact URIs which have been terminated. Each pair in the
         * list is the contact URI and its terminated reason.
         * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is
         * not currently connected to the framework.
         * This can happen if the {@link RcsFeature} is not {@link ImsFeature#STATE_READY} and the
         * {@link RcsFeature} {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not
         * received the {@link ImsFeature#onFeatureReady()} callback. This may also happen in
         * rare cases when the Telephony stack has crashed.
         */
        void onResourceTerminated(
                @NonNull List<Pair<Uri, String>> uriTerminatedReason) throws ImsException;

        /**
         * The subscription associated with a previous
         * {@link RcsUceAdapter#requestCapabilities(List, Executor,
         * RcsUceAdapter.CapabilitiesCallback)}
         * operation has been terminated. This will mostly be due to the network sending a final
         * NOTIFY response due to the subscription expiring, but this may also happen due to a
         * network error.
         *
         * @param reason The reason for the request being unable to process.
         * @param retryAfterMilliseconds The time in milliseconds the requesting application should
         * wait before retrying, if non-zero.
         * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is
         * not currently connected to the framework.
         * This can happen if the {@link RcsFeature} is not {@link ImsFeature#STATE_READY} and the
         * {@link RcsFeature} {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not
         * received the {@link ImsFeature#onFeatureReady()} callback. This may also happen in
         * rare cases when the Telephony stack has crashed.
         */
        void onTerminated(@NonNull String reason, long retryAfterMilliseconds) throws ImsException;
    }

    private final Executor mBinderExecutor;

    /**
     * Create a new RcsCapabilityExchangeImplBase instance.
     *
     * @param executor The executor that remote calls from the framework will be called on.
     */
    public RcsCapabilityExchangeImplBase(@NonNull Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        mBinderExecutor = executor;
    }

    /**
     * The user capabilities of one or multiple contacts have been requested by the framework.
     * <p>
     * The implementer must follow up this call with an
     * {@link SubscribeResponseCallback#onCommandError} call to indicate this operation has failed.
     * The response from the network to the SUBSCRIBE request must be sent back to the framework
     * using {@link SubscribeResponseCallback#onNetworkResponse(int, String)}.
     * As NOTIFY requests come in from the network, the requested contact’s capabilities should be
     * sent back to the framework using
     * {@link SubscribeResponseCallback#onNotifyCapabilitiesUpdate(List<String>}) and
     * {@link SubscribeResponseCallback#onResourceTerminated(List<Pair<Uri, String>>)}
     * should be called with the presence information for the contacts specified.
     * <p>
     * Once the subscription is terminated,
     * {@link SubscribeResponseCallback#onTerminated(String, long)} must be called for the
     * framework to finish listening for NOTIFY responses.
     *
     * @param uris A {@link Collection} of the {@link Uri}s that the framework is requesting the
     * UCE capabilities for.
     * @param cb The callback of the subscribe request.
     */
    // executor used is defined in the constructor.
    @SuppressLint("ExecutorRegistration")
    public void subscribeForCapabilities(@NonNull Collection<Uri> uris,
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
     * {@link PublishResponseCallback#onNetworkResponse(int, String)}.
     * @param pidfXml The XML PIDF document containing the capabilities of this device to be sent
     * to the carrier’s presence server.
     * @param cb The callback of the publish request
     */
    // executor used is defined in the constructor.
    @SuppressLint("ExecutorRegistration")
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
     * The implementer must use {@link OptionsResponseCallback} to send the response of
     * this query from the network back to the framework.
     * @param contactUri The URI of the remote user that we wish to get the capabilities of.
     * @param myCapabilities The capabilities of this device to send to the remote user.
     * @param callback The callback of this request which is sent from the remote user.
     * @hide
     */
    // executor used is defined in the constructor.
    @SuppressLint("ExecutorRegistration")
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

    /**
     * Push one's own capabilities to a remote user via the SIP OPTIONS presence exchange mechanism
     * in order to receive the capabilities of the remote user in response.
     * <p>
     * The implementer must use {@link OptionsResponseCallback} to send the response of
     * this query from the network back to the framework.
     * @param contactUri The URI of the remote user that we wish to get the capabilities of.
     * @param myCapabilities The capabilities of this device to send to the remote user.
     * @param callback The callback of this request which is sent from the remote user.
     */
    // executor used is defined in the constructor.
    @SuppressLint("ExecutorRegistration")
    public void sendOptionsCapabilityRequest(@NonNull Uri contactUri,
            @NonNull Set<String> myCapabilities, @NonNull OptionsResponseCallback callback) {
        // Stub - to be implemented by service
        Log.w(LOG_TAG, "sendOptionsCapabilityRequest called with no implementation.");
        try {
            callback.onCommandError(COMMAND_CODE_NOT_SUPPORTED);
        } catch (ImsException e) {
            // Do not do anything, this is a stub implementation.
        }
    }
}
