/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.telephony.ims.ImsException;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.RcsUceAdapter;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.RcsFeature;

/**
 * The interface of the capabilities event listener for ImsService to notify the framework of the
 * UCE request and status updated.
 * @hide
 */
@SystemApi
public interface CapabilityExchangeEventListener {
    /**
     * Interface used by the framework to respond to OPTIONS requests.
     * @hide
     */
    interface OptionsRequestCallback {
        /**
         * Respond to a remote capability request from the contact specified with the
         * capabilities of this device.
         * @param ownCapabilities The capabilities of this device.
         */
        void onRespondToCapabilityRequest(@NonNull RcsContactUceCapability ownCapabilities);

        /**
         * Respond to a remote capability request from the contact specified with the
         * specified error.
         * @param code The SIP response code to respond with.
         * @param reason A non-null String containing the reason associated with the SIP code.
         */
        void onRespondToCapabilityRequestWithError(int code, @NonNull String reason);
    }

    /**
     * Trigger the framework to provide a capability update using
     * {@link RcsCapabilityExchangeImplBase#publishCapabilities}.
     * <p>
     * This is typically used when trying to generate an initial PUBLISH for a new subscription to
     * the network. The device will cache all presence publications after boot until this method is
     * called the first time.
     * @param publishTriggerType {@link RcsUceAdapter#StackPublishTriggerType} The reason for the
     * capability update request.
     * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is not currently
     * connected to the framework. This can happen if the {@link RcsFeature} is not
     * {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received the
     * {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare cases when the
     * Telephony stack has crashed.
     */
    void onRequestPublishCapabilities(
            @RcsUceAdapter.StackPublishTriggerType int publishTriggerType) throws ImsException;

    /**
     * Notify the framework that the device's capabilities have been unpublished
     * from the network.
     *
     * @throws ImsException If this {@link RcsCapabilityExchangeImplBase} instance is not currently
     * connected to the framework. This can happen if the {@link RcsFeature} is not
     * {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received the
     * {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare cases when the
     * Telephony stack has crashed.
     */
    void onUnpublish() throws ImsException;
}
