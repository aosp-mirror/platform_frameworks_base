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

package android.net.vcn.persistablebundleutils;

import static com.android.internal.annotations.VisibleForTesting.Visibility;

import android.annotation.NonNull;
import android.net.ipsec.ike.ChildSaProposal;
import android.net.ipsec.ike.IkeTrafficSelector;
import android.net.ipsec.ike.TunnelModeChildSessionParams;
import android.os.PersistableBundle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.util.List;
import java.util.Objects;

/**
 * Provides utility methods to convert TunnelModeChildSessionParams to/from PersistableBundle.
 *
 * @hide
 */
@VisibleForTesting(visibility = Visibility.PRIVATE)
public final class TunnelModeChildSessionParamsUtils {
    private static final String INBOUND_TS_KEY = "INBOUND_TS_KEY";
    private static final String OUTBOUND_TS_KEY = "OUTBOUND_TS_KEY";
    private static final String SA_PROPOSALS_KEY = "SA_PROPOSALS_KEY";
    private static final String HARD_LIFETIME_SEC_KEY = "HARD_LIFETIME_SEC_KEY";
    private static final String SOFT_LIFETIME_SEC_KEY = "SOFT_LIFETIME_SEC_KEY";

    /** Serializes a TunnelModeChildSessionParams to a PersistableBundle. */
    @NonNull
    public static PersistableBundle toPersistableBundle(TunnelModeChildSessionParams params) {
        final PersistableBundle result = new PersistableBundle();

        final PersistableBundle saProposalBundle =
                PersistableBundleUtils.fromList(
                        params.getSaProposals(), ChildSaProposalUtils::toPersistableBundle);
        result.putPersistableBundle(SA_PROPOSALS_KEY, saProposalBundle);

        final PersistableBundle inTsBundle =
                PersistableBundleUtils.fromList(
                        params.getInboundTrafficSelectors(),
                        IkeTrafficSelectorUtils::toPersistableBundle);
        result.putPersistableBundle(INBOUND_TS_KEY, inTsBundle);

        final PersistableBundle outTsBundle =
                PersistableBundleUtils.fromList(
                        params.getOutboundTrafficSelectors(),
                        IkeTrafficSelectorUtils::toPersistableBundle);
        result.putPersistableBundle(OUTBOUND_TS_KEY, outTsBundle);

        result.putInt(HARD_LIFETIME_SEC_KEY, params.getHardLifetimeSeconds());
        result.putInt(SOFT_LIFETIME_SEC_KEY, params.getSoftLifetimeSeconds());

        // TODO: b/163604823 Support serializing configuration requests.

        return result;
    }

    private static List<IkeTrafficSelector> getTsFromPersistableBundle(
            PersistableBundle in, String key) {
        PersistableBundle tsBundle = in.getPersistableBundle(key);
        Objects.requireNonNull(tsBundle, "Value for key " + key + " was null");
        return PersistableBundleUtils.toList(
                tsBundle, IkeTrafficSelectorUtils::fromPersistableBundle);
    }

    /** Constructs a TunnelModeChildSessionParams by deserializing a PersistableBundle. */
    @NonNull
    public static TunnelModeChildSessionParams fromPersistableBundle(
            @NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle was null");

        final TunnelModeChildSessionParams.Builder builder =
                new TunnelModeChildSessionParams.Builder();

        final PersistableBundle proposalBundle = in.getPersistableBundle(SA_PROPOSALS_KEY);
        Objects.requireNonNull(proposalBundle, "SA proposal was null");
        final List<ChildSaProposal> proposals =
                PersistableBundleUtils.toList(
                        proposalBundle, ChildSaProposalUtils::fromPersistableBundle);
        for (ChildSaProposal p : proposals) {
            builder.addSaProposal(p);
        }

        for (IkeTrafficSelector ts : getTsFromPersistableBundle(in, INBOUND_TS_KEY)) {
            builder.addInboundTrafficSelectors(ts);
        }

        for (IkeTrafficSelector ts : getTsFromPersistableBundle(in, OUTBOUND_TS_KEY)) {
            builder.addOutboundTrafficSelectors(ts);
        }

        builder.setLifetimeSeconds(
                in.getInt(HARD_LIFETIME_SEC_KEY), in.getInt(SOFT_LIFETIME_SEC_KEY));

        // TODO: b/163604823 Support deserializing configuration requests.

        return builder.build();
    }
}
