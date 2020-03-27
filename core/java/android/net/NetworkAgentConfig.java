/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Allows a network transport to provide the system with policy and configuration information about
 * a particular network when registering a {@link NetworkAgent}. This information cannot change once the agent is registered.
 *
 * @hide
 */
@SystemApi
public final class NetworkAgentConfig implements Parcelable {

    /**
     * If the {@link Network} is a VPN, whether apps are allowed to bypass the
     * VPN. This is set by a {@link VpnService} and used by
     * {@link ConnectivityManager} when creating a VPN.
     *
     * @hide
     */
    public boolean allowBypass;

    /**
     * Set if the network was manually/explicitly connected to by the user either from settings
     * or a 3rd party app.  For example, turning on cell data is not explicit but tapping on a wifi
     * ap in the wifi settings to trigger a connection is explicit.  A 3rd party app asking to
     * connect to a particular access point is also explicit, though this may change in the future
     * as we want apps to use the multinetwork apis.
     *
     * @hide
     */
    public boolean explicitlySelected;

    /**
     * @return whether this network was explicitly selected by the user.
     */
    public boolean isExplicitlySelected() {
        return explicitlySelected;
    }

    /**
     * Set if the user desires to use this network even if it is unvalidated. This field has meaning
     * only if {@link explicitlySelected} is true. If it is, this field must also be set to the
     * appropriate value based on previous user choice.
     *
     * TODO : rename this field to match its accessor
     * @hide
     */
    public boolean acceptUnvalidated;

    /**
     * @return whether the system should accept this network even if it doesn't validate.
     */
    public boolean isUnvalidatedConnectivityAcceptable() {
        return acceptUnvalidated;
    }

    /**
     * Whether the user explicitly set that this network should be validated even if presence of
     * only partial internet connectivity.
     *
     * TODO : rename this field to match its accessor
     * @hide
     */
    public boolean acceptPartialConnectivity;

    /**
     * @return whether the system should validate this network even if it only offers partial
     *     Internet connectivity.
     */
    public boolean isPartialConnectivityAcceptable() {
        return acceptPartialConnectivity;
    }

    /**
     * Set to avoid surfacing the "Sign in to network" notification.
     * if carrier receivers/apps are registered to handle the carrier-specific provisioning
     * procedure, a carrier specific provisioning notification will be placed.
     * only one notification should be displayed. This field is set based on
     * which notification should be used for provisioning.
     *
     * @hide
     */
    public boolean provisioningNotificationDisabled;

    /**
     *
     * @return whether the sign in to network notification is enabled by this configuration.
     * @hide
     */
    public boolean isProvisioningNotificationEnabled() {
        return !provisioningNotificationDisabled;
    }

    /**
     * For mobile networks, this is the subscriber ID (such as IMSI).
     *
     * @hide
     */
    public String subscriberId;

    /**
     * @return the subscriber ID, or null if none.
     * @hide
     */
    @Nullable
    public String getSubscriberId() {
        return subscriberId;
    }

    /**
     * Set to skip 464xlat. This means the device will treat the network as IPv6-only and
     * will not attempt to detect a NAT64 via RFC 7050 DNS lookups.
     *
     * @hide
     */
    public boolean skip464xlat;

    /**
     * @return whether NAT64 prefix detection is enabled.
     * @hide
     */
    public boolean isNat64DetectionEnabled() {
        return !skip464xlat;
    }

    /**
     * The legacy type of this network agent, or TYPE_NONE if unset.
     * @hide
     */
    public int legacyType = ConnectivityManager.TYPE_NONE;

    /**
     * @return the legacy type
     */
    @ConnectivityManager.LegacyNetworkType
    public int getLegacyType() {
        return legacyType;
    }

    /**
     * Set to true if the PRIVATE_DNS_BROKEN notification has shown for this network.
     * Reset this bit when private DNS mode is changed from strict mode to opportunistic/off mode.
     *
     * This is not parceled, because it would not make sense.
     *
     * @hide
     */
    public transient boolean hasShownBroken;

    /**
     * The name of the legacy network type. It's a free-form string used in logging.
     * @hide
     */
    @NonNull
    public String legacyTypeName = "";

    /**
     * @return the name of the legacy network type. It's a free-form string used in logging.
     */
    @NonNull
    public String getLegacyTypeName() {
        return legacyTypeName;
    }

    /** @hide */
    public NetworkAgentConfig() {
    }

    /** @hide */
    public NetworkAgentConfig(@Nullable NetworkAgentConfig nac) {
        if (nac != null) {
            allowBypass = nac.allowBypass;
            explicitlySelected = nac.explicitlySelected;
            acceptUnvalidated = nac.acceptUnvalidated;
            acceptPartialConnectivity = nac.acceptPartialConnectivity;
            subscriberId = nac.subscriberId;
            provisioningNotificationDisabled = nac.provisioningNotificationDisabled;
            skip464xlat = nac.skip464xlat;
            legacyType = nac.legacyType;
            legacyTypeName = nac.legacyTypeName;
        }
    }

    /**
     * Builder class to facilitate constructing {@link NetworkAgentConfig} objects.
     */
    public static final class Builder {
        private final NetworkAgentConfig mConfig = new NetworkAgentConfig();

        /**
         * Sets whether the network was explicitly selected by the user.
         *
         * @return this builder, to facilitate chaining.
         */
        @NonNull
        public Builder setExplicitlySelected(final boolean explicitlySelected) {
            mConfig.explicitlySelected = explicitlySelected;
            return this;
        }

        /**
         * Sets whether the system should validate this network even if it is found not to offer
         * Internet connectivity.
         *
         * @return this builder, to facilitate chaining.
         */
        @NonNull
        public Builder setUnvalidatedConnectivityAcceptable(
                final boolean unvalidatedConnectivityAcceptable) {
            mConfig.acceptUnvalidated = unvalidatedConnectivityAcceptable;
            return this;
        }

        /**
         * Sets whether the system should validate this network even if it is found to only offer
         * partial Internet connectivity.
         *
         * @return this builder, to facilitate chaining.
         */
        @NonNull
        public Builder setPartialConnectivityAcceptable(
                final boolean partialConnectivityAcceptable) {
            mConfig.acceptPartialConnectivity = partialConnectivityAcceptable;
            return this;
        }

        /**
         * Sets the subscriber ID for this network.
         *
         * @return this builder, to facilitate chaining.
         * @hide
         */
        @NonNull
        public Builder setSubscriberId(@Nullable String subscriberId) {
            mConfig.subscriberId = subscriberId;
            return this;
        }

        /**
         * Disables active detection of NAT64 (e.g., via RFC 7050 DNS lookups). Used to save power
         * and reduce idle traffic on networks that are known to be IPv6-only without a NAT64.
         *
         * @return this builder, to facilitate chaining.
         * @hide
         */
        @NonNull
        public Builder disableNat64Detection() {
            mConfig.skip464xlat = true;
            return this;
        }

        /**
         * Disables the "Sign in to network" notification. Used if the network transport will
         * perform its own carrier-specific provisioning procedure.
         *
         * @return this builder, to facilitate chaining.
         * @hide
         */
        @NonNull
        public Builder disableProvisioningNotification() {
            mConfig.provisioningNotificationDisabled = true;
            return this;
        }

        /**
         * Sets the legacy type for this network.
         *
         * @param legacyType the type
         * @return this builder, to facilitate chaining.
         */
        @NonNull
        public Builder setLegacyType(int legacyType) {
            mConfig.legacyType = legacyType;
            return this;
        }

        /**
         * Sets the name of the legacy type of the agent. It's a free-form string used in logging.
         * @param legacyTypeName the name
         * @return this builder, to facilitate chaining.
         */
        @NonNull
        public Builder setLegacyTypeName(@NonNull String legacyTypeName) {
            mConfig.legacyTypeName = legacyTypeName;
            return this;
        }

        /**
         * Returns the constructed {@link NetworkAgentConfig} object.
         */
        @NonNull
        public NetworkAgentConfig build() {
            return mConfig;
        }
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final NetworkAgentConfig that = (NetworkAgentConfig) o;
        return allowBypass == that.allowBypass
                && explicitlySelected == that.explicitlySelected
                && acceptUnvalidated == that.acceptUnvalidated
                && acceptPartialConnectivity == that.acceptPartialConnectivity
                && provisioningNotificationDisabled == that.provisioningNotificationDisabled
                && skip464xlat == that.skip464xlat
                && legacyType == that.legacyType
                && Objects.equals(subscriberId, that.subscriberId)
                && Objects.equals(legacyTypeName, that.legacyTypeName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowBypass, explicitlySelected, acceptUnvalidated,
                acceptPartialConnectivity, provisioningNotificationDisabled, subscriberId,
                skip464xlat, legacyType, legacyTypeName);
    }

    @Override
    public String toString() {
        return "NetworkAgentConfig {"
                + " allowBypass = " + allowBypass
                + ", explicitlySelected = " + explicitlySelected
                + ", acceptUnvalidated = " + acceptUnvalidated
                + ", acceptPartialConnectivity = " + acceptPartialConnectivity
                + ", provisioningNotificationDisabled = " + provisioningNotificationDisabled
                + ", subscriberId = '" + subscriberId + '\''
                + ", skip464xlat = " + skip464xlat
                + ", legacyType = " + legacyType
                + ", hasShownBroken = " + hasShownBroken
                + ", legacyTypeName = '" + legacyTypeName + '\''
                + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(allowBypass ? 1 : 0);
        out.writeInt(explicitlySelected ? 1 : 0);
        out.writeInt(acceptUnvalidated ? 1 : 0);
        out.writeInt(acceptPartialConnectivity ? 1 : 0);
        out.writeString(subscriberId);
        out.writeInt(provisioningNotificationDisabled ? 1 : 0);
        out.writeInt(skip464xlat ? 1 : 0);
        out.writeInt(legacyType);
        out.writeString(legacyTypeName);
    }

    public static final @NonNull Creator<NetworkAgentConfig> CREATOR =
            new Creator<NetworkAgentConfig>() {
        @Override
        public NetworkAgentConfig createFromParcel(Parcel in) {
            NetworkAgentConfig networkAgentConfig = new NetworkAgentConfig();
            networkAgentConfig.allowBypass = in.readInt() != 0;
            networkAgentConfig.explicitlySelected = in.readInt() != 0;
            networkAgentConfig.acceptUnvalidated = in.readInt() != 0;
            networkAgentConfig.acceptPartialConnectivity = in.readInt() != 0;
            networkAgentConfig.subscriberId = in.readString();
            networkAgentConfig.provisioningNotificationDisabled = in.readInt() != 0;
            networkAgentConfig.skip464xlat = in.readInt() != 0;
            networkAgentConfig.legacyType = in.readInt();
            networkAgentConfig.legacyTypeName = in.readString();
            return networkAgentConfig;
        }

        @Override
        public NetworkAgentConfig[] newArray(int size) {
            return new NetworkAgentConfig[size];
        }
    };
}
