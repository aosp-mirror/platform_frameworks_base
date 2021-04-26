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

package android.telephony.ims;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.telephony.ims.stub.SipDelegate;
import android.telephony.ims.stub.SipTransportImplBase;

import java.util.Set;

/**
 * Callback interface to notify a remote application of the following:
 * <ul>
 *     <li>the {@link SipDelegate} associated with this callback has been created or destroyed in
 *         response to a creation or destruction request from the framework</li>
 *     <li>the SIP IMS configuration associated with this {@link SipDelegate} has changed</li>
 *     <li>the IMS registration of the feature tags associated with this {@link SipDelegate} have
 *         changed.</li>
 * </ul>
 * @hide
 */
@SystemApi
public interface DelegateStateCallback {

    /**
     * This must be called by the ImsService after {@link SipTransportImplBase#createSipDelegate} is
     * called by the framework to notify the framework and remote application that the
     * {@link SipDelegate} has been successfully created.
     *  @param delegate The SipDelegate created to service the DelegateRequest.
     * @param deniedTags A Set of {@link FeatureTagState}s, which contain the feature tags
     *    associated with this {@link SipDelegate} that have no access to send/receive SIP messages
     *    as well as a reason for why the feature tag is denied. For more information on the reason
     *    why the feature tag was denied access, see the
     *    {@link SipDelegateManager.DeniedReason} reasons. This is considered a permanent denial due
     *    to this {@link SipDelegate} not supporting a feature or this ImsService already
     *    implementing this feature elsewhere. If all features of this {@link SipDelegate} are
     *    denied, this method should still be called.
     */
    void onCreated(@NonNull SipDelegate delegate,
            @SuppressLint("NullableCollection")  // TODO(b/154763999): Mark deniedTags @Nonnull
            @Nullable Set<FeatureTagState> deniedTags);

    /**
     * This must be called by the ImsService after the framework calls
     * {@link SipTransportImplBase#destroySipDelegate} to notify the framework and remote
     * application that the procedure to destroy the {@link SipDelegate} has been completed.
     * @param reasonCode The reason for closing this delegate.
     */
    void onDestroyed(@SipDelegateManager.SipDelegateDestroyReason int reasonCode);

    /**
     * Call to notify the remote application of a configuration change associated with this
     * {@link SipDelegate}.
     * <p>
     * The remote application will not be able to proceed sending SIP messages until after this
     * configuration is sent the first time, so this configuration should be sent as soon as the
     * {@link SipDelegate} has access to these configuration parameters.
     * <p>
     * Incoming SIP messages should not be routed to the remote application until AFTER this
     * configuration change is sent to ensure that the remote application can respond correctly.
     * Similarly, if there is an event that triggers the IMS configuration to change, incoming SIP
     * messages routing should be delayed until the {@link SipDelegate} sends the IMS configuration
     * change event to reduce conditions where the remote application is using a stale IMS
     * configuration.
     * @deprecated This is being removed from API surface, Use
     * {@link #onConfigurationChanged(SipDelegateConfiguration)} instead.
     */
    @Deprecated
    void onImsConfigurationChanged(@NonNull SipDelegateImsConfiguration config);

    /**
     * Call to notify the remote application of a configuration change associated with this
     * {@link SipDelegate}.
     * <p>
     * The remote application will not be able to proceed sending SIP messages until after this
     * configuration is sent the first time, so this configuration should be sent as soon as the
     * {@link SipDelegate} has access to these configuration parameters.
     * <p>
     * Incoming SIP messages should not be routed to the remote application until AFTER this
     * configuration change is sent to ensure that the remote application can respond correctly.
     * Similarly, if there is an event that triggers the IMS configuration to change, incoming SIP
     * messages routing should be delayed until the {@link SipDelegate} sends the IMS configuration
     * change event to reduce conditions where the remote application is using a stale IMS
     * configuration.
     */
    void onConfigurationChanged(@NonNull SipDelegateConfiguration config);

    /**
     * Call to notify the remote application that the {@link SipDelegate} has modified the IMS
     * registration state of the RCS feature tags that were requested as part of the initial
     * {@link DelegateRequest}.
     * <p>
     * See {@link DelegateRegistrationState} for more information about how IMS Registration state
     * should be communicated the associated SipDelegateConnection in cases such as
     * IMS deregistration, handover, PDN change, provisioning changes, etc…
     * <p>
     * Note: Even after the status of the feature tags are updated here to deregistered, the
     * SipDelegate must still be able to handle these messages and call
     * {@link DelegateMessageCallback#onMessageSendFailure} to notify the RCS application that the
     * message was not sent.
     *
     * @param registrationState The current network IMS registration state for all feature tags
     *         associated with this SipDelegate.
     */
    void onFeatureTagRegistrationChanged(@NonNull DelegateRegistrationState registrationState);
}
