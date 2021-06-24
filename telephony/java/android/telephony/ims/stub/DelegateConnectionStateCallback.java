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
import android.telephony.ims.DelegateRegistrationState;
import android.telephony.ims.DelegateRequest;
import android.telephony.ims.FeatureTagState;
import android.telephony.ims.SipDelegateConfiguration;
import android.telephony.ims.SipDelegateConnection;
import android.telephony.ims.SipDelegateImsConfiguration;
import android.telephony.ims.SipDelegateManager;

import java.util.Set;

/**
 * The callback associated with a {@link SipDelegateConnection} that manages the state of the
 * SipDelegateConnection.
 * <p>
 * After {@link SipDelegateManager#createSipDelegate} is used to request a new
 * {@link SipDelegateConnection} be created, {@link #onCreated} will be called with the
 * {@link SipDelegateConnection} instance that must be used to communicate with the remote
 * {@link SipDelegate}.
 * <p>
 * After, {@link #onFeatureTagStatusChanged} will always be called at least once with the current
 * status of the feature tags that have been requested. The application may receive multiple
 * {@link #onFeatureTagStatusChanged} callbacks over the lifetime of the associated
 * {@link SipDelegateConnection}, which will signal changes to how SIP messages associated with
 * those feature tags will be handled.
 * <p>
 * In order to start sending SIP messages, the SIP configuration parameters will need to be
 * received, so the messaging application should make no assumptions about these parameters and wait
 * until {@link #onConfigurationChanged(SipDelegateConfiguration)} has been called. This is
 * guaranteed to happen after the first {@link #onFeatureTagStatusChanged} if there is at least one
 * feature tag that has been successfully associated with the {@link SipDelegateConnection}. If all
 * feature tags were denied, no IMS configuration will be sent.
 * <p>
 * The {@link SipDelegateConnection} will stay associated with this RCS application until either the
 * RCS application calls {@link SipDelegateManager#destroySipDelegate} or telephony destroys the
 * {@link SipDelegateConnection}. In both cases, {@link #onDestroyed(int)}  will be called.
 * Telephony destroying the {@link SipDelegateConnection} instance is rare and will only happen in
 * rare cases, such as if telephony itself or IMS service dies unexpectedly. See
 * {@link SipDelegateManager.SipDelegateDestroyReason} reasons for more information on all of the
 * cases that will trigger the {@link SipDelegateConnection} to be destroyed.
 *
 * @hide
 */
@SystemApi
public interface DelegateConnectionStateCallback {

    /**
     * A {@link SipDelegateConnection} has been successfully created for the
     * {@link DelegateRequest} used when calling {@link SipDelegateManager#createSipDelegate}.
     */
    void onCreated(@NonNull SipDelegateConnection c);

    /**
     * The status of the RCS feature tags that were requested as part of the initial
     * {@link DelegateRequest}.
     * <p>
     * There are four states that each RCS feature tag can be in: registered, deregistering,
     * deregistered, and denied.
     * <p>
     * When a feature tag is considered registered, SIP messages associated with that feature tag
     * may be sent and received freely.
     * <p>
     * When a feature tag is deregistering, the network IMS registration still contains the feature
     * tag, however the IMS service and associated {@link SipDelegate} is in the progress of
     * modifying the IMS registration to remove this feature tag and requires the application to
     * perform an action before the IMS registration can change. The specific action required for
     * the SipDelegate to continue modifying the IMS registration can be found in the definition of
     * each {@link DelegateRegistrationState.DeregisteringReason}.
     * <p>
     * When a feature tag is in the deregistered state, new out-of-dialog SIP messages for that
     * feature tag will be rejected, however due to network race conditions, the RCS application
     * should still be able to handle new out-of-dialog SIP requests from the network. This may not
     * be possible, however, if the IMS registration itself was lost. See the
     * {@link DelegateRegistrationState.DeregisteredReason} reasons for more information on how SIP
     * messages are handled in each of these cases.
     * <p>
     * If a feature tag is denied, no incoming messages will be routed to the associated
     * {@link DelegateConnectionMessageCallback} and all outgoing SIP messages related to this
     * feature tag will be rejected. See {@link SipDelegateManager.DeniedReason}
     * reasons for more information about the conditions when this will happen.
     * <p>
     * The set of feature tags contained in the registered, deregistering, deregistered, and denied
     * lists will always equal the set of feature tags requested in the initial
     * {@link DelegateRequest}.
     * <p>
     * Transitions of feature tags from registered, deregistering, and deregistered and vice-versa
     * may happen quite often, however transitions to/from denied are rare and only occur if the
     * user has changed the role of your application to add/remove support for one or more requested
     * feature tags or carrier provisioning has enabled or disabled single registration entirely.
     * Please see {@link SipDelegateManager.DeniedReason} reasons for an explanation of each of
     * these cases as well as what may cause them to change.
     *
     * @param registrationState The new IMS registration state of each of the feature tags
     *     associated with the {@link SipDelegate}.
     * @param deniedFeatureTags A list of {@link FeatureTagState} objects, each containing a feature
     *     tag associated with this {@link SipDelegateConnection} that has no access to
     *     send/receive SIP messages as well as a reason for why the feature tag is denied. For more
     *     information on the reason why the feature tag was denied access, see the
     *     {@link SipDelegateManager.DeniedReason} reasons.
     */
    void onFeatureTagStatusChanged(@NonNull DelegateRegistrationState registrationState,
            @NonNull Set<FeatureTagState> deniedFeatureTags);


    /**
     * IMS configuration of the underlying IMS stack used by this IMS application for construction
     * of the SIP messages that will be sent over the carrier's network.
     * <p>
     * There should never be assumptions made about the configuration of the underling IMS stack and
     * the IMS application should wait for this indication before sending out any outgoing SIP
     * messages.
     * <p>
     * Configuration may change due to IMS registration changes as well as
     * other optional events on the carrier network. If IMS stack is already registered at the time
     * of callback registration, then this method shall be invoked with the current configuration.
     * Otherwise, there may be a delay in this method being called if initial IMS registration has
     * not compleed yet.
     *
     * @param registeredSipConfig The configuration of the IMS stack registered on the IMS network.
     * @deprecated Will not be in final API, use
     * {@link #onConfigurationChanged(SipDelegateConfiguration)} instead}.
     */
    @Deprecated
    default void onImsConfigurationChanged(
            @NonNull SipDelegateImsConfiguration registeredSipConfig) {
        onConfigurationChanged(registeredSipConfig.toNewConfig());
    }

    /**
     * IMS configuration of the underlying IMS stack used by this IMS application for construction
     * of the SIP messages that will be sent over the carrier's network.
     * <p>
     * There should never be assumptions made about the configuration of the underling IMS stack and
     * the IMS application should wait for this indication before sending out any outgoing SIP
     * messages.
     * <p>
     * Configuration may change due to IMS registration changes as well as
     * other optional events on the carrier network. If IMS stack is already registered at the time
     * of callback registration, then this method shall be invoked with the current configuration.
     * Otherwise, there may be a delay in this method being called if initial IMS registration has
     * not compleed yet.
     *
     * @param registeredSipConfig The configuration of the IMS stack registered on the IMS network.
     */
    default void onConfigurationChanged(@NonNull SipDelegateConfiguration registeredSipConfig) {}

    /**
     * The previously created {@link SipDelegateConnection} instance delivered via
     * {@link #onCreated(SipDelegateConnection)} has been destroyed. This interface should no longer
     * be used for any SIP message handling.
     *
     * @param reason The reason for the failure.
     */
    void onDestroyed(@SipDelegateManager.SipDelegateDestroyReason int reason);
}
