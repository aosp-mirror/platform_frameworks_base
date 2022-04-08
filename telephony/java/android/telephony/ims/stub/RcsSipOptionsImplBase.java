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
import android.annotation.Nullable;
import android.net.Uri;
import android.os.RemoteException;
import android.telephony.ims.ImsException;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.RcsFeature;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base implementation for RCS User Capability Exchange using SIP OPTIONS.
 *
 * @hide
 */
public class RcsSipOptionsImplBase extends RcsCapabilityExchange {

    private static final String LOG_TAG = "RcsSipOptionsImplBase";

    /**
     * Indicates a SIP response from the remote user other than 200, 480, 408, 404, or 604.
     */
    public static final int RESPONSE_GENERIC_FAILURE = -1;

    /**
     * Indicates that the remote user responded with a 200 OK response.
     */
    public static final int RESPONSE_SUCCESS = 0;

    /**
     * Indicates that the remote user responded with a 480 TEMPORARY UNAVAILABLE response.
     */
    public static final int RESPONSE_TEMPORARILY_UNAVAILABLE = 1;

    /**
     * Indicates that the remote user responded with a 408 REQUEST TIMEOUT response.
     */
    public static final int RESPONSE_REQUEST_TIMEOUT = 2;

    /**
     * Indicates that the remote user responded with a 404 NOT FOUND response.
     */
    public static final int RESPONSE_NOT_FOUND = 3;

    /**
     * Indicates that the remote user responded with a 604 DOES NOT EXIST ANYWHERE response.
     */
    public static final int RESPONSE_DOES_NOT_EXIST_ANYWHERE = 4;

    /**
     * Indicates that the remote user responded with a 400 BAD REQUEST response.
     */
    public static final int RESPONSE_BAD_REQUEST = 5;

    /** @hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "RESPONSE_", value = {
            RESPONSE_GENERIC_FAILURE,
            RESPONSE_SUCCESS,
            RESPONSE_TEMPORARILY_UNAVAILABLE,
            RESPONSE_REQUEST_TIMEOUT,
            RESPONSE_NOT_FOUND,
            RESPONSE_DOES_NOT_EXIST_ANYWHERE,
            RESPONSE_BAD_REQUEST
    })
    public @interface SipResponseCode {}

    /**
     * Send the response of a SIP OPTIONS capability exchange to the framework. If {@code code} is
     * {@link #RESPONSE_SUCCESS}, info must be non-null.
     * @param code The SIP response code that was sent by the network in response to the request
     *        sent by {@link #sendCapabilityRequest(Uri, RcsContactUceCapability, int)}.
     * @param reason The optional SIP response reason sent by the network. If none was sent, this
     *        should be an empty string.
     * @param info the contact's UCE capabilities associated with the capability request.
     * @param operationToken The token associated with the original capability request, set by
     *        {@link #sendCapabilityRequest(Uri, RcsContactUceCapability, int)}.
     * @throws ImsException If this {@link RcsSipOptionsImplBase} instance is not currently
     * connected to the framework. This can happen if the {@link RcsFeature} is not
     * {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received the
     * {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare cases when the
     * Telephony stack has crashed.
     */
    public final void onCapabilityRequestResponse(@SipResponseCode int code, @NonNull String reason,
            @Nullable RcsContactUceCapability info, int operationToken) throws ImsException {
        try {
            getListener().onCapabilityRequestResponseOptions(code, reason, info, operationToken);
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Inform the framework of a query for this device's UCE capabilities.
     * <p>
     * The framework will respond via the
     * {@link #respondToCapabilityRequest(String, RcsContactUceCapability, int)} or
     * {@link #respondToCapabilityRequestWithError(Uri, int, String, int)} method.
     * @param contactUri The URI associated with the remote contact that is requesting capabilities.
     * @param remoteInfo The remote contact's capability information.
     * @param operationToken An unique operation token that you have generated that will be returned
     *         by the framework in
     *         {@link #respondToCapabilityRequest(String, RcsContactUceCapability, int)}.
     * @throws ImsException If this {@link RcsSipOptionsImplBase} instance is not currently
     * connected to the framework. This can happen if the {@link RcsFeature} is not
     * {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received the
     * {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare cases when the
     * Telephony stack has crashed.
     */
    public final void onRemoteCapabilityRequest(@NonNull Uri contactUri,
            @NonNull RcsContactUceCapability remoteInfo, int operationToken) throws ImsException {
        try {
            getListener().onRemoteCapabilityRequest(contactUri, remoteInfo, operationToken);
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Push one's own capabilities to a remote user via the SIP OPTIONS presence exchange mechanism
     * in order to receive the capabilities of the remote user in response.
     * <p>
     * The implementer must call
     * {@link #onCapabilityRequestResponse(int, String, RcsContactUceCapability, int)} to send the
     * response of this query back to the framework.
     * @param contactUri The URI of the remote user that we wish to get the capabilities of.
     * @param capabilities The capabilities of this device to send to the remote user.
     * @param operationToken A token generated by the framework that will be passed through
     * {@link #onCapabilityRequestResponse(int, String, RcsContactUceCapability, int)} when this
     *         operation has succeeded.
     */
    public void sendCapabilityRequest(@NonNull Uri contactUri,
            @NonNull RcsContactUceCapability capabilities, int operationToken) {
        // Stub - to be implemented by service
        Log.w(LOG_TAG, "sendCapabilityRequest called with no implementation.");
        try {
            getListener().onCommandUpdate(COMMAND_CODE_NOT_SUPPORTED, operationToken);
        } catch (RemoteException | ImsException e) {
            // Do not do anything, this is a stub implementation.
        }
    }

    /**
     * Respond to a remote capability request from the contact specified with the capabilities of
     * this device.
     * <p>
     * The framework will use the same token and uri as what was passed in to
     * {@link #onRemoteCapabilityRequest(Uri, RcsContactUceCapability, int)}.
     * @param contactUri The URI of the remote contact.
     * @param ownCapabilities The capabilities of this device.
     * @param operationToken The token generated by the framework that this service obtained when
     *         {@link #onRemoteCapabilityRequest(Uri, RcsContactUceCapability, int)} was called.
     */
    public void respondToCapabilityRequest(@NonNull String contactUri,
            @NonNull RcsContactUceCapability ownCapabilities, int operationToken) {
        // Stub - to be implemented by service
        Log.w(LOG_TAG, "respondToCapabilityRequest called with no implementation.");
        try {
            getListener().onCommandUpdate(COMMAND_CODE_NOT_SUPPORTED, operationToken);
        } catch (RemoteException | ImsException e) {
            // Do not do anything, this is a stub implementation.
        }
    }

    /**
     * Respond to a remote capability request from the contact specified with the specified error.
     * <p>
     * The framework will use the same token and uri as what was passed in to
     * {@link #onRemoteCapabilityRequest(Uri, RcsContactUceCapability, int)}.
     * @param contactUri A URI containing the remote contact.
     * @param code The SIP response code to respond with.
     * @param reason A non-null String containing the reason associated with the SIP code.
     * @param operationToken The token provided by the framework when
     *         {@link #onRemoteCapabilityRequest(Uri, RcsContactUceCapability, int)} was called.
     */
    public void respondToCapabilityRequestWithError(@NonNull Uri contactUri,
            @SipResponseCode int code, @NonNull String reason, int operationToken) {
        // Stub - to be implemented by service
        Log.w(LOG_TAG, "respondToCapabiltyRequestWithError called with no implementation.");
        try {
            getListener().onCommandUpdate(COMMAND_CODE_NOT_SUPPORTED, operationToken);
        } catch (RemoteException | ImsException e) {
            // Do not do anything, this is a stub implementation.
        }
    }
}
