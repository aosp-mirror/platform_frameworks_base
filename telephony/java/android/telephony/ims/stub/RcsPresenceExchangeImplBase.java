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

package android.telephony.ims.stub;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.net.Uri;
import android.os.RemoteException;
import android.telephony.ims.ImsException;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.RcsFeature;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Base implementation for RCS User Capability Exchange using Presence. Any ImsService implementing
 * this service must implement the stub methods {@link #requestCapabilities(List, int)}  and
 * {@link #updateCapabilities(RcsContactUceCapability, int)}.
 *
 * @hide
 */
public class RcsPresenceExchangeImplBase extends RcsCapabilityExchange {

    private static final String LOG_TAG = "RcsPresenceExchangeIB";

    /**
     * The request has resulted in any other 4xx/5xx/6xx that is not covered below. No retry will be
     * attempted.
     */
    public static final int RESPONSE_SUBSCRIBE_GENERIC_FAILURE = -1;

    /**
     * The request has succeeded with a “200” message from the network.
     */
    public static final int RESPONSE_SUCCESS = 0;

    /**
     * The request has resulted in a “403” (User Not Registered) error from the network. Will retry
     * capability polling with an exponential backoff.
     */
    public static final int RESPONSE_NOT_REGISTERED = 1;

    /**
     * The request has resulted in a “403” (not authorized (Requestor)) error from the network. No
     * retry will be attempted.
     */
    public static final int RESPONSE_NOT_AUTHORIZED_FOR_PRESENCE = 2;

    /**
     * The request has resulted in a "403” (Forbidden) or other “403” error from the network and
     * will be handled the same as “404” Not found. No retry will be attempted.
     */
    public static final int RESPONSE_FORBIDDEN = 3;

    /**
     * The request has resulted in a “404” (Not found) result from the network. No retry will be
     * attempted.
     */
    public static final int RESPONSE_NOT_FOUND = 4;

    /**
     * The request has resulted in a “408” response. Retry after exponential backoff.
     */
    public static final int RESPONSE_SIP_REQUEST_TIMEOUT = 5;

    /**
     *  The network has responded with a “413” (Too Large) response from the network. Capability
     *  request contains too many items and must be shrunk before the request will be accepted.
     */
    public static final int RESPONSE_SUBSCRIBE_TOO_LARGE = 6;

    /**
     * The request has resulted in a “423” response. Retry after exponential backoff.
     */
    public static final int RESPONSE_SIP_INTERVAL_TOO_SHORT = 7;

    /**
     * The request has resulted in a “503” response. Retry after exponential backoff.
     */
    public static final int RESPONSE_SIP_SERVICE_UNAVAILABLE = 8;

    /** @hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "RESPONSE_", value = {
            RESPONSE_SUBSCRIBE_GENERIC_FAILURE,
            RESPONSE_SUCCESS,
            RESPONSE_NOT_REGISTERED,
            RESPONSE_NOT_AUTHORIZED_FOR_PRESENCE,
            RESPONSE_FORBIDDEN,
            RESPONSE_NOT_FOUND,
            RESPONSE_SIP_REQUEST_TIMEOUT,
            RESPONSE_SUBSCRIBE_TOO_LARGE,
            RESPONSE_SIP_INTERVAL_TOO_SHORT,
            RESPONSE_SIP_SERVICE_UNAVAILABLE
    })
    public @interface PresenceResponseCode {}

    /**
     * Provide the framework with a subsequent network response update to
     * {@link #updateCapabilities(RcsContactUceCapability, int)} and
     * {@link #requestCapabilities(List, int)} operations.
     *
     * @param code The SIP response code sent from the network for the operation token specified.
     * @param reason The optional reason response from the network. If the network provided no
     *         reason with the code, the string should be empty.
     * @param operationToken The token associated with the operation this service is providing a
     *         response for.
     * @throws ImsException If this {@link RcsPresenceExchangeImplBase} instance is not currently
     * connected to the framework. This can happen if the {@link RcsFeature} is not
     * {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received the
     * {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare cases when the
     * Telephony stack has crashed.
     */
    public final void onNetworkResponse(@PresenceResponseCode int code, @NonNull String reason,
            int operationToken) throws ImsException {
        try {
            getListener().onNetworkResponse(code, reason, operationToken);
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Provides the framework with the requested contacts’ capabilities requested by the framework
     * using {@link #requestCapabilities(List, int)}.
     *
     * @throws ImsException If this {@link RcsPresenceExchangeImplBase} instance is not currently
     * connected to the framework. This can happen if the {@link RcsFeature} is not
     * {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received the
     * {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare cases when the
     * Telephony stack has crashed.
     */
    public final void onCapabilityRequestResponse(@NonNull List<RcsContactUceCapability> infos,
            int operationToken) throws ImsException {
        try {
            getListener().onCapabilityRequestResponsePresence(infos, operationToken);
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Trigger the framework to provide a capability update using
     * {@link #updateCapabilities(RcsContactUceCapability, int)}.
     * <p>
     * This is typically used when trying to generate an initial PUBLISH for a new subscription to
     * the network. The device will cache all presence publications after boot until this method is
     * called once.
     * @throws ImsException If this {@link RcsPresenceExchangeImplBase} instance is not currently
     * connected to the framework. This can happen if the {@link RcsFeature} is not
     * {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received the
     * {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare cases when the
     * Telephony stack has crashed.
     */
    public final void onNotifyUpdateCapabilites() throws ImsException {
        try {
            getListener().onNotifyUpdateCapabilities();
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Notify the framework that the device’s capabilities have been unpublished from the network.
     *
     * @throws ImsException If this {@link RcsPresenceExchangeImplBase} instance is not currently
     * connected to the framework. This can happen if the {@link RcsFeature} is not
     * {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received the
     * {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare cases when the
     * Telephony stack has crashed.
     */
    public final void onUnpublish() throws ImsException {
        try {
            getListener().onUnpublish();
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * The user capabilities of one or multiple contacts have been requested by the framework.
     * <p>
     * The implementer must follow up this call with an {@link #onCommandUpdate(int, int)} call to
     * indicate whether or not this operation succeeded.  If this operation succeeds, network
     * response updates should be sent to the framework using
     * {@link #onNetworkResponse(int, String, int)}. When the operation is completed,
     * {@link #onCapabilityRequestResponse(List, int)} should be called with the presence
     * information for the contacts specified.
     * @param uris A {@link List} of the {@link Uri}s that the framework is requesting the UCE
     *             capabilities for.
     * @param operationToken The token associated with this operation. Updates to this request using
     *         {@link #onCommandUpdate(int, int)}, {@link #onNetworkResponse(int, String, int)}, and
     *         {@link #onCapabilityRequestResponse(List, int)}  must use the same operation token
     *         in response.
     */
    public void requestCapabilities(@NonNull List<Uri> uris, int operationToken) {
        // Stub - to be implemented by service
        Log.w(LOG_TAG, "requestCapabilities called with no implementation.");
        try {
            getListener().onCommandUpdate(COMMAND_CODE_NOT_SUPPORTED, operationToken);
        } catch (RemoteException | ImsException e) {
            // Do not do anything, this is a stub implementation.
        }
    }

    /**
     * The capabilities of this device have been updated and should be published to the network.
     * <p>
     * The implementer must follow up this call with an {@link #onCommandUpdate(int, int)} call to
     * indicate whether or not this operation succeeded. If this operation succeeds, network
     * response updates should be sent to the framework using
     * {@link #onNetworkResponse(int, String, int)}.
     * @param capabilities The capabilities for this device.
     * @param operationToken The token associated with this operation. Any subsequent
     *         {@link #onCommandUpdate(int, int)} or {@link #onNetworkResponse(int, String, int)}
     *         calls regarding this update must use the same token.
     */
    public void updateCapabilities(@NonNull RcsContactUceCapability capabilities,
            int operationToken) {
        // Stub - to be implemented by service
        Log.w(LOG_TAG, "updateCapabilities called with no implementation.");
        try {
            getListener().onCommandUpdate(COMMAND_CODE_NOT_SUPPORTED, operationToken);
        } catch (RemoteException | ImsException e) {
            // Do not do anything, this is a stub implementation.
        }
    }
}
