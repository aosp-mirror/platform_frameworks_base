/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.connectivity;

import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkScore.KEEP_CONNECTED_NONE;
import static android.net.NetworkScore.POLICY_EXITING;
import static android.net.NetworkScore.POLICY_TRANSPORT_PRIMARY;
import static android.net.NetworkScore.POLICY_YIELD_TO_BAD_WIFI;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkScore;
import android.net.NetworkScore.KeepConnectedReason;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.StringJoiner;

/**
 * This class represents how desirable a network is.
 *
 * FullScore is very similar to NetworkScore, but it contains the bits that are managed
 * by ConnectivityService. This provides static guarantee that all users must know whether
 * they are handling a score that had the CS-managed bits set.
 */
public class FullScore {
    // This will be removed soon. Do *NOT* depend on it for any new code that is not part of
    // a migration.
    private final int mLegacyInt;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"POLICY_"}, value = {
            POLICY_IS_VALIDATED,
            POLICY_IS_VPN,
            POLICY_EVER_USER_SELECTED,
            POLICY_ACCEPT_UNVALIDATED,
            POLICY_IS_UNMETERED
    })
    public @interface Policy {
    }

    // Agent-managed policies are in NetworkScore. They start from 1.
    // CS-managed policies, counting from 63 downward
    // This network is validated. CS-managed because the source of truth is in NetworkCapabilities.
    /** @hide */
    public static final int POLICY_IS_VALIDATED = 63;

    // This is a VPN and behaves as one for scoring purposes.
    /** @hide */
    public static final int POLICY_IS_VPN = 62;

    // This network has been selected by the user manually from settings or a 3rd party app
    // at least once. {@see NetworkAgentConfig#explicitlySelected}.
    /** @hide */
    public static final int POLICY_EVER_USER_SELECTED = 61;

    // The user has indicated in UI that this network should be used even if it doesn't
    // validate. {@see NetworkAgentConfig#acceptUnvalidated}.
    /** @hide */
    public static final int POLICY_ACCEPT_UNVALIDATED = 60;

    // This network is unmetered. {@see NetworkCapabilities.NET_CAPABILITY_NOT_METERED}.
    /** @hide */
    public static final int POLICY_IS_UNMETERED = 59;

    // This network is invincible. This is useful for offers until there is an API to listen
    // to requests.
    /** @hide */
    public static final int POLICY_IS_INVINCIBLE = 58;

    // This network has been validated at least once since it was connected, but not explicitly
    // avoided in UI.
    // TODO : remove setAvoidUnvalidated and instead disconnect the network when the user
    // chooses to move away from this network, and remove this flag.
    /** @hide */
    public static final int POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD = 57;

    // To help iterate when printing
    @VisibleForTesting
    static final int MIN_CS_MANAGED_POLICY = POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD;
    @VisibleForTesting
    static final int MAX_CS_MANAGED_POLICY = POLICY_IS_VALIDATED;

    // Mask for policies in NetworkScore. This should have all bits managed by NetworkScore set
    // and all bits managed by FullScore unset. As bits are handled from 0 up in NetworkScore and
    // from 63 down in FullScore, cut at the 32nd bit for simplicity, but change this if some day
    // there are more than 32 bits handled on either side.
    // YIELD_TO_BAD_WIFI is temporarily handled by ConnectivityService, but the factory is still
    // allowed to set it, so that it's possible to transition from handling it in CS to handling
    // it in the factory.
    private static final long EXTERNAL_POLICIES_MASK = 0x00000000FFFFFFFFL;

    @VisibleForTesting
    static @NonNull String policyNameOf(final int policy) {
        switch (policy) {
            case POLICY_IS_VALIDATED: return "IS_VALIDATED";
            case POLICY_IS_VPN: return "IS_VPN";
            case POLICY_EVER_USER_SELECTED: return "EVER_USER_SELECTED";
            case POLICY_ACCEPT_UNVALIDATED: return "ACCEPT_UNVALIDATED";
            case POLICY_IS_UNMETERED: return "IS_UNMETERED";
            case POLICY_YIELD_TO_BAD_WIFI: return "YIELD_TO_BAD_WIFI";
            case POLICY_TRANSPORT_PRIMARY: return "TRANSPORT_PRIMARY";
            case POLICY_EXITING: return "EXITING";
            case POLICY_IS_INVINCIBLE: return "INVINCIBLE";
            case POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD: return "EVER_VALIDATED";
        }
        throw new IllegalArgumentException("Unknown policy : " + policy);
    }

    // Bitmask of all the policies applied to this score.
    private final long mPolicies;

    private final int mKeepConnectedReason;

    FullScore(final int legacyInt, final long policies,
            @KeepConnectedReason final int keepConnectedReason) {
        mLegacyInt = legacyInt;
        mPolicies = policies;
        mKeepConnectedReason = keepConnectedReason;
    }

    /**
     * Given a score supplied by the NetworkAgent and CS-managed objects, produce a full score.
     *
     * @param score the score supplied by the agent
     * @param caps the NetworkCapabilities of the network
     * @param config the NetworkAgentConfig of the network
     * @param everValidated whether this network has ever validated
     * @param yieldToBadWiFi whether this network yields to a previously validated wifi gone bad
     * @return a FullScore that is appropriate to use for ranking.
     */
    // TODO : this shouldn't manage bad wifi avoidance – instead this should be done by the
    // telephony factory, so that it depends on the carrier. For now this is handled by
    // connectivity for backward compatibility.
    public static FullScore fromNetworkScore(@NonNull final NetworkScore score,
            @NonNull final NetworkCapabilities caps, @NonNull final NetworkAgentConfig config,
            final boolean everValidated, final boolean yieldToBadWiFi) {
        return withPolicies(score.getLegacyInt(), score.getPolicies(),
                score.getKeepConnectedReason(),
                caps.hasCapability(NET_CAPABILITY_VALIDATED),
                caps.hasTransport(TRANSPORT_VPN),
                caps.hasCapability(NET_CAPABILITY_NOT_METERED),
                everValidated,
                config.explicitlySelected,
                config.acceptUnvalidated,
                yieldToBadWiFi,
                false /* invincible */); // only prospective scores can be invincible
    }

    /**
     * Given a score supplied by a NetworkProvider, produce a prospective score for an offer.
     *
     * NetworkOffers have score filters that are compared to the scores of actual networks
     * to see if they could possibly beat the current satisfier. Some things the agent can't
     * know in advance ; a good example is the validation bit – some networks will validate,
     * others won't. For comparison purposes, assume the best, so all possibly beneficial
     * networks will be brought up.
     *
     * @param score the score supplied by the agent for this offer
     * @param caps the capabilities supplied by the agent for this offer
     * @return a FullScore appropriate for comparing to actual network's scores.
     */
    public static FullScore makeProspectiveScore(@NonNull final NetworkScore score,
            @NonNull final NetworkCapabilities caps) {
        // If the network offers Internet access, it may validate.
        final boolean mayValidate = caps.hasCapability(NET_CAPABILITY_INTERNET);
        // VPN transports are known in advance.
        final boolean vpn = caps.hasTransport(TRANSPORT_VPN);
        // Prospective scores are always unmetered, because unmetered networks are stronger
        // than metered networks, and it's not known in advance whether the network is metered.
        final boolean unmetered = true;
        // If the offer may validate, then it should be considered to have validated at some point
        final boolean everValidated = mayValidate;
        // The network hasn't been chosen by the user (yet, at least).
        final boolean everUserSelected = false;
        // Don't assume the user will accept unvalidated connectivity.
        final boolean acceptUnvalidated = false;
        // Don't assume clinging to bad wifi
        final boolean yieldToBadWiFi = false;
        // A prospective score is invincible if the legacy int in the filter is over the maximum
        // score.
        final boolean invincible = score.getLegacyInt() > NetworkRanker.LEGACY_INT_MAX;
        return withPolicies(score.getLegacyInt(), score.getPolicies(), KEEP_CONNECTED_NONE,
                mayValidate, vpn, unmetered, everValidated, everUserSelected, acceptUnvalidated,
                yieldToBadWiFi, invincible);
    }

    /**
     * Return a new score given updated caps and config.
     *
     * @param caps the NetworkCapabilities of the network
     * @param config the NetworkAgentConfig of the network
     * @return a score with the policies from the arguments reset
     */
    // TODO : this shouldn't manage bad wifi avoidance – instead this should be done by the
    // telephony factory, so that it depends on the carrier. For now this is handled by
    // connectivity for backward compatibility.
    public FullScore mixInScore(@NonNull final NetworkCapabilities caps,
            @NonNull final NetworkAgentConfig config,
            final boolean everValidated,
            final boolean yieldToBadWifi) {
        return withPolicies(mLegacyInt, mPolicies, mKeepConnectedReason,
                caps.hasCapability(NET_CAPABILITY_VALIDATED),
                caps.hasTransport(TRANSPORT_VPN),
                caps.hasCapability(NET_CAPABILITY_NOT_METERED),
                everValidated,
                config.explicitlySelected,
                config.acceptUnvalidated,
                yieldToBadWifi,
                false /* invincible */); // only prospective scores can be invincible
    }

    // TODO : this shouldn't manage bad wifi avoidance – instead this should be done by the
    // telephony factory, so that it depends on the carrier. For now this is handled by
    // connectivity for backward compatibility.
    private static FullScore withPolicies(@NonNull final int legacyInt,
            final long externalPolicies,
            @KeepConnectedReason final int keepConnectedReason,
            final boolean isValidated,
            final boolean isVpn,
            final boolean isUnmetered,
            final boolean everValidated,
            final boolean everUserSelected,
            final boolean acceptUnvalidated,
            final boolean yieldToBadWiFi,
            final boolean invincible) {
        return new FullScore(legacyInt, (externalPolicies & EXTERNAL_POLICIES_MASK)
                | (isValidated       ? 1L << POLICY_IS_VALIDATED : 0)
                | (isVpn             ? 1L << POLICY_IS_VPN : 0)
                | (isUnmetered       ? 1L << POLICY_IS_UNMETERED : 0)
                | (everValidated     ? 1L << POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD : 0)
                | (everUserSelected  ? 1L << POLICY_EVER_USER_SELECTED : 0)
                | (acceptUnvalidated ? 1L << POLICY_ACCEPT_UNVALIDATED : 0)
                | (yieldToBadWiFi    ? 1L << POLICY_YIELD_TO_BAD_WIFI : 0)
                | (invincible        ? 1L << POLICY_IS_INVINCIBLE : 0),
                keepConnectedReason);
    }

    /**
     * Returns this score but validated.
     */
    public FullScore asValidated() {
        return new FullScore(mLegacyInt, mPolicies | (1L << POLICY_IS_VALIDATED),
                mKeepConnectedReason);
    }

    /**
     * For backward compatibility, get the legacy int.
     * This will be removed before S is published.
     */
    public int getLegacyInt() {
        return getLegacyInt(false /* pretendValidated */);
    }

    public int getLegacyIntAsValidated() {
        return getLegacyInt(true /* pretendValidated */);
    }

    // TODO : remove these two constants
    // Penalty applied to scores of Networks that have not been validated.
    private static final int UNVALIDATED_SCORE_PENALTY = 40;

    // Score for a network that can be used unvalidated
    private static final int ACCEPT_UNVALIDATED_NETWORK_SCORE = 100;

    private int getLegacyInt(boolean pretendValidated) {
        // If the user has chosen this network at least once, give it the maximum score when
        // checking to pretend it's validated, or if it doesn't need to validate because the
        // user said to use it even if it doesn't validate.
        // This ensures that networks that have been selected in UI are not torn down before the
        // user gets a chance to prefer it when a higher-scoring network (e.g., Ethernet) is
        // available.
        if (hasPolicy(POLICY_EVER_USER_SELECTED)
                && (hasPolicy(POLICY_ACCEPT_UNVALIDATED) || pretendValidated)) {
            return ACCEPT_UNVALIDATED_NETWORK_SCORE;
        }

        int score = mLegacyInt;
        // Except for VPNs, networks are subject to a penalty for not being validated.
        // Apply the penalty unless the network is a VPN, or it's validated or pretending to be.
        if (!hasPolicy(POLICY_IS_VALIDATED) && !pretendValidated && !hasPolicy(POLICY_IS_VPN)) {
            score -= UNVALIDATED_SCORE_PENALTY;
        }
        if (score < 0) score = 0;
        return score;
    }

    /**
     * @return whether this score has a particular policy.
     */
    @VisibleForTesting
    public boolean hasPolicy(final int policy) {
        return 0 != (mPolicies & (1L << policy));
    }

    /**
     * Returns the keep-connected reason, or KEEP_CONNECTED_NONE.
     */
    public int getKeepConnectedReason() {
        return mKeepConnectedReason;
    }

    // Example output :
    // Score(50 ; Policies : EVER_USER_SELECTED&IS_VALIDATED)
    @Override
    public String toString() {
        final StringJoiner sj = new StringJoiner(
                "&", // delimiter
                "Score(" + mLegacyInt + " ; KeepConnected : " + mKeepConnectedReason
                        + " ; Policies : ", // prefix
                ")"); // suffix
        for (int i = NetworkScore.MIN_AGENT_MANAGED_POLICY;
                i <= NetworkScore.MAX_AGENT_MANAGED_POLICY; ++i) {
            if (hasPolicy(i)) sj.add(policyNameOf(i));
        }
        for (int i = MIN_CS_MANAGED_POLICY; i <= MAX_CS_MANAGED_POLICY; ++i) {
            if (hasPolicy(i)) sj.add(policyNameOf(i));
        }
        return sj.toString();
    }
}
