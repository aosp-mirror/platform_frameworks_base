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
 * limitations under the License
 */

package android.telephony.ims.feature;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.telephony.ims.aidl.IImsRcsFeature;
import android.telephony.ims.stub.RcsPresenceExchangeImplBase;
import android.telephony.ims.stub.RcsSipOptionsImplBase;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base implementation of the RcsFeature APIs. Any ImsService wishing to support RCS should extend
 * this class and provide implementations of the RcsFeature methods that they support.
 * @hide
 */
@SystemApi
public class RcsFeature extends ImsFeature {

    /**{@inheritDoc}*/
    private final IImsRcsFeature mImsRcsBinder = new IImsRcsFeature.Stub() {
        // Empty Default Implementation.
    };

    /**
     * Contains the capabilities defined and supported by a {@link RcsFeature} in the
     * form of a bitmask. The capabilities that are used in the RcsFeature are
     * defined as:
     * {@link RcsImsCapabilityFlag#CAPABILITY_TYPE_OPTIONS_UCE}
     * {@link RcsImsCapabilityFlag#CAPABILITY_TYPE_PRESENCE_UCE}
     *
     * The enabled capabilities of this RcsFeature will be set by the framework
     * using {@link #changeEnabledCapabilities(CapabilityChangeRequest, CapabilityCallbackProxy)}.
     * After the capabilities have been set, the RcsFeature may then perform the necessary bring up
     * of the capability and notify the capability status as true using
     * {@link #notifyCapabilitiesStatusChanged(RcsImsCapabilities)}. This will signal to the
     * framework that the capability is available for usage.
     * @hide
     */
    public static class RcsImsCapabilities extends Capabilities {
        /** @hide*/
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = "CAPABILITY_TYPE_", flag = true, value = {
                CAPABILITY_TYPE_OPTIONS_UCE,
                CAPABILITY_TYPE_PRESENCE_UCE
        })
        public @interface RcsImsCapabilityFlag {}

        /**
         * This carrier supports User Capability Exchange using SIP OPTIONS as defined by the
         * framework. If set, the RcsFeature should support capability exchange using SIP OPTIONS.
         * If not set, this RcsFeature should not service capability requests.
         * @hide
         */
        public static final int CAPABILITY_TYPE_OPTIONS_UCE = 1 << 0;

        /**
         * This carrier supports User Capability Exchange using a presence server as defined by the
         * framework. If set, the RcsFeature should support capability exchange using a presence
         * server. If not set, this RcsFeature should not publish capabilities or service capability
         * requests using presence.
         * @hide
         */
        public static final int CAPABILITY_TYPE_PRESENCE_UCE =  1 << 1;

        /**@hide*/
        public RcsImsCapabilities(@RcsImsCapabilityFlag int capabilities) {

        }

        /**@hide*/
        @Override
        public void addCapabilities(@RcsImsCapabilityFlag int capabilities) {

        }

        /**@hide*/
        @Override
        public void removeCapabilities(@RcsImsCapabilityFlag int capabilities) {

        }

        /**@hide*/
        @Override
        public boolean isCapable(@RcsImsCapabilityFlag int capabilities) {
            return false;
        }
    }
    /**
     * Query the current {@link RcsImsCapabilities} status set by the RcsFeature. If a capability is
     * set, the {@link RcsFeature} has brought up the capability and is ready for framework
     * requests. To change the status of the capabilities
     * {@link #notifyCapabilitiesStatusChanged(RcsImsCapabilities)} should be called.
     * @hide
     */
    @Override
    public final RcsImsCapabilities queryCapabilityStatus() {
        throw new UnsupportedOperationException();
    }

    /**
     * Notify the framework that the capabilities status has changed. If a capability is enabled,
     * this signals to the framework that the capability has been initialized and is ready.
     * Call {@link #queryCapabilityStatus()} to return the current capability status.
     * @hide
     */
    public final void notifyCapabilitiesStatusChanged(RcsImsCapabilities c) {
        throw new UnsupportedOperationException();
    }

    /**
     * Provides the RcsFeature with the ability to return the framework capability configuration set
     * by the framework. When the framework calls
     * {@link #changeEnabledCapabilities(CapabilityChangeRequest, CapabilityCallbackProxy)} to
     * enable or disable capability A, this method should return the correct configuration for
     * capability A afterwards (until it has changed).
     * @hide
     */
    public boolean queryCapabilityConfiguration(
            @RcsImsCapabilities.RcsImsCapabilityFlag int capability) {
        throw new UnsupportedOperationException();
    }
    /**
     * Called from the framework when the {@link RcsImsCapabilities} that have been configured for
     * this {@link RcsFeature} has changed.
     * <p>
     * For each newly enabled capability flag, the corresponding capability should be brought up in
     * the {@link RcsFeature} and registered on the network. For each newly disabled capability
     * flag, the corresponding capability should be brought down, and deregistered. Once a new
     * capability has been initialized and is ready for usage, the status of that capability should
     * also be set to true using {@link #notifyCapabilitiesStatusChanged(RcsImsCapabilities)}. This
     * will notify the framework that the capability is ready.
     * <p>
     * If for some reason one or more of these capabilities can not be enabled/disabled,
     * {@link CapabilityCallbackProxy#onChangeCapabilityConfigurationError(int, int, int)} should
     * be called for each capability change that resulted in an error.
     * @hide
     */
    @Override
    public void changeEnabledCapabilities(CapabilityChangeRequest request,
            CapabilityCallbackProxy c) {
        throw new UnsupportedOperationException();
    }

    /**
     * Retrieve the implementation of SIP OPTIONS for this {@link RcsFeature}.
     * <p>
     * Will only be requested by the framework if capability exchange via SIP OPTIONS is
     * configured as capable during a
     * {@link #changeEnabledCapabilities(CapabilityChangeRequest, CapabilityCallbackProxy)}
     * operation and the RcsFeature sets the status of the capability to true using
     * {@link #notifyCapabilitiesStatusChanged(RcsImsCapabilities)}.
     *
     * @return An instance of {@link RcsSipOptionsImplBase} that implements SIP options exchange if
     * it is supported by the device.
     * @hide
     */
    public RcsSipOptionsImplBase getOptionsExchangeImpl() {
        // Base Implementation, override to implement functionality
        return new RcsSipOptionsImplBase();
    }

    /**
     * Retrieve the implementation of UCE presence for this {@link RcsFeature}.
     * Will only be requested by the framework if presence exchang is configured as capable during
     * a {@link #changeEnabledCapabilities(CapabilityChangeRequest, CapabilityCallbackProxy)}
     * operation and the RcsFeature sets the status of the capability to true using
     * {@link #notifyCapabilitiesStatusChanged(RcsImsCapabilities)}.
     *
     * @return An instance of {@link RcsPresenceExchangeImplBase} that implements presence
     * exchange if it is supported by the device.
     * @hide
     */
    public RcsPresenceExchangeImplBase getPresenceExchangeImpl() {
        // Base Implementation, override to implement functionality.
        return new RcsPresenceExchangeImplBase();
    }

    /**
     * Construct a new {@link RcsFeature} instance.
     */
    public RcsFeature() {
        super();
    }

    /**{@inheritDoc}*/
    @Override
    public void onFeatureRemoved() {

    }

    /**{@inheritDoc}*/
    @Override
    public void onFeatureReady() {

    }

    /**
     * @hide
     */
    @Override
    public final IImsRcsFeature getBinder() {
        return mImsRcsBinder;
    }
}
