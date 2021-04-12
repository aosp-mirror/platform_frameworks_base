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

package com.android.server.connectivity;

import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkScore.POLICY_EXITING;
import static android.net.NetworkScore.POLICY_TRANSPORT_PRIMARY;
import static android.net.NetworkScore.POLICY_YIELD_TO_BAD_WIFI;

import static com.android.server.connectivity.FullScore.POLICY_ACCEPT_UNVALIDATED;
import static com.android.server.connectivity.FullScore.POLICY_EVER_USER_SELECTED;
import static com.android.server.connectivity.FullScore.POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD;
import static com.android.server.connectivity.FullScore.POLICY_IS_INVINCIBLE;
import static com.android.server.connectivity.FullScore.POLICY_IS_VALIDATED;
import static com.android.server.connectivity.FullScore.POLICY_IS_VPN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import com.android.net.module.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * A class that knows how to find the best network matching a request out of a list of networks.
 */
public class NetworkRanker {
    // Historically the legacy ints have been 0~100 in principle (though the highest score in
    // AOSP has always been 90). This is relied on by VPNs that send a legacy score of 101.
    public static final int LEGACY_INT_MAX = 100;

    /**
     * A class that can be scored against other scoreables.
     */
    public interface Scoreable {
        /** Get score of this scoreable */
        FullScore getScore();
        /** Get capabilities of this scoreable */
        NetworkCapabilities getCapsNoCopy();
    }

    private static final boolean USE_POLICY_RANKING = false;

    public NetworkRanker() { }

    // TODO : move to module utils CollectionUtils.
    @NonNull private static <T> ArrayList<T> filter(@NonNull final Collection<T> source,
            @NonNull final Predicate<T> test) {
        final ArrayList<T> matches = new ArrayList<>();
        for (final T e : source) {
            if (test.test(e)) {
                matches.add(e);
            }
        }
        return matches;
    }

    /**
     * Find the best network satisfying this request among the list of passed networks.
     */
    @Nullable
    public NetworkAgentInfo getBestNetwork(@NonNull final NetworkRequest request,
            @NonNull final Collection<NetworkAgentInfo> nais,
            @Nullable final NetworkAgentInfo currentSatisfier) {
        final ArrayList<NetworkAgentInfo> candidates = filter(nais, nai -> nai.satisfies(request));
        if (candidates.size() == 1) return candidates.get(0); // Only one potential satisfier
        if (candidates.size() <= 0) return null; // No network can satisfy this request
        if (USE_POLICY_RANKING) {
            return getBestNetworkByPolicy(candidates, currentSatisfier);
        } else {
            return getBestNetworkByLegacyInt(candidates);
        }
    }

    // Transport preference order, if it comes down to that.
    private static final int[] PREFERRED_TRANSPORTS_ORDER = { TRANSPORT_ETHERNET, TRANSPORT_WIFI,
            TRANSPORT_BLUETOOTH, TRANSPORT_CELLULAR };

    // Function used to partition a list into two working areas depending on whether they
    // satisfy a predicate. All items satisfying the predicate will be put in |positive|, all
    // items that don't will be put in |negative|.
    // This is useful in this file because many of the ranking checks will retain only networks that
    // satisfy a predicate if any of them do, but keep them all if all of them do. Having working
    // areas is uncustomary in Java, but this function is called in a fairly intensive manner
    // and doing allocation quite that often might affect performance quite badly.
    private static <T> void partitionInto(@NonNull final List<T> source, @NonNull Predicate<T> test,
            @NonNull final List<T> positive, @NonNull final List<T> negative) {
        positive.clear();
        negative.clear();
        for (final T item : source) {
            if (test.test(item)) {
                positive.add(item);
            } else {
                negative.add(item);
            }
        }
    }

    @Nullable private <T extends Scoreable> T getBestNetworkByPolicy(
            @NonNull List<T> candidates,
            @Nullable final T currentSatisfier) {
        // Used as working areas.
        final ArrayList<T> accepted =
                new ArrayList<>(candidates.size() /* initialCapacity */);
        final ArrayList<T> rejected =
                new ArrayList<>(candidates.size() /* initialCapacity */);

        // The following tests will search for a network matching a given criterion. They all
        // function the same way : if any network matches the criterion, drop from consideration
        // all networks that don't. To achieve this, the tests below :
        // 1. partition the list of remaining candidates into accepted and rejected networks.
        // 2. if only one candidate remains, that's the winner : if accepted.size == 1 return [0]
        // 3. if multiple remain, keep only the accepted networks and go on to the next criterion.
        //    Because the working areas will be wiped, a copy of the accepted networks needs to be
        //    made.
        // 4. if none remain, the criterion did not help discriminate so keep them all. As an
        //    optimization, skip creating a new array and go on to the next criterion.

        // If a network is invincible, use it.
        partitionInto(candidates, nai -> nai.getScore().hasPolicy(POLICY_IS_INVINCIBLE),
                accepted, rejected);
        if (accepted.size() == 1) return accepted.get(0);
        if (accepted.size() > 0 && rejected.size() > 0) candidates = new ArrayList<>(accepted);

        // If there is a connected VPN, use it.
        partitionInto(candidates, nai -> nai.getScore().hasPolicy(POLICY_IS_VPN),
                accepted, rejected);
        if (accepted.size() == 1) return accepted.get(0);
        if (accepted.size() > 0 && rejected.size() > 0) candidates = new ArrayList<>(accepted);

        // Selected & Accept-unvalidated policy : if any network has both of these, then don't
        // choose one that doesn't.
        partitionInto(candidates, nai -> nai.getScore().hasPolicy(POLICY_EVER_USER_SELECTED)
                        && nai.getScore().hasPolicy(POLICY_ACCEPT_UNVALIDATED),
                accepted, rejected);
        if (accepted.size() == 1) return accepted.get(0);
        if (accepted.size() > 0 && rejected.size() > 0) candidates = new ArrayList<>(accepted);

        // Yield to bad wifi policy : if any wifi has ever been validated (even if it's now
        // unvalidated), and unless it's been explicitly avoided when bad in UI, then keep only
        // networks that don't yield to such a wifi network.
        final boolean anyWiFiEverValidated = CollectionUtils.any(candidates,
                nai -> nai.getScore().hasPolicy(POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD)
                        && nai.getCapsNoCopy().hasTransport(TRANSPORT_WIFI));
        if (anyWiFiEverValidated) {
            partitionInto(candidates, nai -> !nai.getScore().hasPolicy(POLICY_YIELD_TO_BAD_WIFI),
                    accepted, rejected);
            if (accepted.size() == 1) return accepted.get(0);
            if (accepted.size() > 0 && rejected.size() > 0) candidates = new ArrayList<>(accepted);
        }

        // If any network is validated (or should be accepted even if it's not validated), then
        // don't choose one that isn't.
        partitionInto(candidates, nai -> nai.getScore().hasPolicy(POLICY_IS_VALIDATED)
                        || nai.getScore().hasPolicy(POLICY_ACCEPT_UNVALIDATED),
                accepted, rejected);
        if (accepted.size() == 1) return accepted.get(0);
        if (accepted.size() > 0 && rejected.size() > 0) candidates = new ArrayList<>(accepted);

        // If any network is not exiting, don't choose one that is.
        partitionInto(candidates, nai -> !nai.getScore().hasPolicy(POLICY_EXITING),
                accepted, rejected);
        if (accepted.size() == 1) return accepted.get(0);
        if (accepted.size() > 0 && rejected.size() > 0) candidates = new ArrayList<>(accepted);

        // TODO : If any network is unmetered, don't choose a metered network.
        // This can't be implemented immediately because prospective networks are always
        // considered unmetered because factories don't know if the network will be metered.
        // Saying an unmetered network always beats a metered one would mean that when metered wifi
        // is connected, the offer for telephony would beat WiFi but the actual metered network
        // would lose, so we'd have an infinite loop where telephony would continually bring up
        // a network that is immediately torn down.
        // Fix this by getting the agent to tell connectivity whether the network they will
        // bring up is metered. Cell knows that in advance, while WiFi has a good estimate and
        // can revise it if the network later turns out to be metered.
        // partitionInto(candidates, nai -> nai.getScore().hasPolicy(POLICY_IS_UNMETERED),
        //         accepted, rejected);
        // if (accepted.size() == 1) return accepted.get(0);
        // if (accepted.size() > 0 && rejected.size() > 0) candidates = new ArrayList<>(accepted);

        // If any network is for the default subscription, don't choose a network for another
        // subscription with the same transport.
        partitionInto(candidates, nai -> nai.getScore().hasPolicy(POLICY_TRANSPORT_PRIMARY),
                accepted, rejected);
        for (final Scoreable defaultSubNai : accepted) {
            // Remove all networks without the DEFAULT_SUBSCRIPTION policy and the same transports
            // as a network that has it.
            final int[] transports = defaultSubNai.getCapsNoCopy().getTransportTypes();
            candidates.removeIf(nai -> !nai.getScore().hasPolicy(POLICY_TRANSPORT_PRIMARY)
                    && Arrays.equals(transports, nai.getCapsNoCopy().getTransportTypes()));
        }
        if (1 == candidates.size()) return candidates.get(0);
        // It's guaranteed candidates.size() > 0 because there is at least one with the
        // TRANSPORT_PRIMARY policy and only those without it were removed.

        // If some of the networks have a better transport than others, keep only the ones with
        // the best transports.
        for (final int transport : PREFERRED_TRANSPORTS_ORDER) {
            partitionInto(candidates, nai -> nai.getCapsNoCopy().hasTransport(transport),
                    accepted, rejected);
            if (accepted.size() == 1) return accepted.get(0);
            if (accepted.size() > 0 && rejected.size() > 0) {
                candidates = new ArrayList<>(accepted);
                break;
            }
        }

        // At this point there are still multiple networks passing all the tests above. If any
        // of them is the previous satisfier, keep it.
        if (candidates.contains(currentSatisfier)) return currentSatisfier;

        // If there are still multiple options at this point but none of them is any of the
        // transports above, it doesn't matter which is returned. They are all the same.
        return candidates.get(0);
    }

    // TODO : switch to the policy implementation and remove
    // Almost equivalent to Collections.max(nais), but allows returning null if no network
    // satisfies the request.
    private NetworkAgentInfo getBestNetworkByLegacyInt(
            @NonNull final Collection<NetworkAgentInfo> nais) {
        NetworkAgentInfo bestNetwork = null;
        int bestScore = Integer.MIN_VALUE;
        for (final NetworkAgentInfo nai : nais) {
            if (nai.getCurrentScore() > bestScore) {
                bestNetwork = nai;
                bestScore = nai.getCurrentScore();
            }
        }
        return bestNetwork;
    }

    /**
     * Returns whether an offer has a chance to beat a champion network for a request.
     *
     * Offers are sent by network providers when they think they might be able to make a network
     * with the characteristics contained in the offer. If the offer has no chance to beat
     * the currently best network for a given request, there is no point in the provider spending
     * power trying to find and bring up such a network.
     *
     * Note that having an offer up does not constitute a commitment from the provider part
     * to be able to bring up a network with these characteristics, or a network at all for
     * that matter. This is only used to save power by letting providers know when they can't
     * beat a current champion.
     *
     * @param request The request to evaluate against.
     * @param champion The currently best network for this request.
     * @param offer The offer.
     * @return Whether the offer stands a chance to beat the champion.
     */
    public boolean mightBeat(@NonNull final NetworkRequest request,
            @Nullable final NetworkAgentInfo champion,
            @NonNull final NetworkOffer offer) {
        // If this network can't even satisfy the request then it can't beat anything, not
        // even an absence of network. It can't satisfy it anyway.
        if (!request.canBeSatisfiedBy(offer.caps)) return false;
        // If there is no satisfying network, then this network can beat, because some network
        // is always better than no network.
        if (null == champion) return true;
        if (USE_POLICY_RANKING) {
            // If there is no champion, the offer can always beat.
            // Otherwise rank them.
            final ArrayList<Scoreable> candidates = new ArrayList<>();
            candidates.add(champion);
            candidates.add(offer);
            return offer == getBestNetworkByPolicy(candidates, champion);
        } else {
            return mightBeatByLegacyInt(request, champion.getScore(), offer);
        }
    }

    /**
     * Returns whether an offer might beat a champion according to the legacy int.
     */
    public boolean mightBeatByLegacyInt(@NonNull final NetworkRequest request,
            @Nullable final FullScore championScore,
            @NonNull final NetworkOffer offer) {
        final int offerIntScore;
        if (offer.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            // If the offer might have Internet access, then it might validate.
            offerIntScore = offer.score.getLegacyIntAsValidated();
        } else {
            offerIntScore = offer.score.getLegacyInt();
        }
        return championScore.getLegacyInt() < offerIntScore;
    }
}
