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

package android.telephony.ims.aidl;

import android.net.Uri;
import android.telephony.ims.aidl.IOptionsRequestCallback;

import java.util.List;

/**
 * Listener interface for the ImsService to use to notify the framework of UCE events.
 * {@hide}
 */
oneway interface ICapabilityExchangeEventListener {
    /**
     * Trigger the framework to provide a capability update using
     * {@link RcsCapabilityExchangeImplBase#publishCapabilities}.
     * <p>
     * This is typically used when trying to generate an initial PUBLISH for a new
     * subscription to the network. The device will cache all presence publications
     * after boot until this method is called the first time.
     * @param publishTriggerType {@link StackPublishTriggerType} The reason for the
     * capability update request.
     * @throws ImsException If this {@link RcsPresenceExchangeImplBase} instance is
     * not currently connected to the framework. This can happen if the
     * {@link RcsFeature} is not {@link ImsFeature#STATE_READY} and the
     * {@link RcsFeature} has not received the
     * {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare
     * cases when the Telephony stack has crashed.
     */
    void onRequestPublishCapabilities(int publishTriggerType);

    /**
     * Notify the framework that the device's capabilities have been unpublished from the network.
     *
     * @throws ImsException If this {@link RcsPresenceExchangeImplBase} instance is not currently
     * connected to the framework. This can happen if the {@link RcsFeature} is not
     * {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received the
     * {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare cases when the
     * Telephony stack has crashed.
     */
    void onUnpublish();

    /**
     * Inform the framework of a query for this device's UCE capabilities.
     * <p>
     * The framework will respond via the
     * {@link IOptionsRequestCallback#respondToCapabilityRequest} or
     * {@link IOptionsRequestCallback#respondToCapabilityRequestWithError} method.
     * @param contactUri The URI associated with the remote contact that is requesting capabilities.
     * @param remoteCapabilities The remote contact's capability information.
     * @throws ImsException If this {@link RcsSipOptionsImplBase} instance is not currently
     * connected to the framework. This can happen if the {@link RcsFeature} is not
     * {@link ImsFeature#STATE_READY} and the {@link RcsFeature} has not received
     * the {@link ImsFeature#onFeatureReady()} callback. This may also happen in rare cases when
     * the Telephony stack has crashed.
     */
    void onRemoteCapabilityRequest(in Uri contactUri,
            in List<String> remoteCapabilities,
            IOptionsRequestCallback cb);
}
