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
import android.os.PersistableBundle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.util.List;
import java.util.Objects;

/**
 * Provides utility methods to convert ChildSaProposal to/from PersistableBundle.
 *
 * @hide
 */
@VisibleForTesting(visibility = Visibility.PRIVATE)
public final class ChildSaProposalUtils extends SaProposalUtilsBase {
    /** Serializes a ChildSaProposal to a PersistableBundle. */
    @NonNull
    public static PersistableBundle toPersistableBundle(ChildSaProposal proposal) {
        return SaProposalUtilsBase.toPersistableBundle(proposal);
    }

    /** Constructs a ChildSaProposal by deserializing a PersistableBundle. */
    @NonNull
    public static ChildSaProposal fromPersistableBundle(@NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle was null");

        final ChildSaProposal.Builder builder = new ChildSaProposal.Builder();

        final PersistableBundle encryptionBundle = in.getPersistableBundle(ENCRYPT_ALGO_KEY);
        Objects.requireNonNull(encryptionBundle, "Encryption algo bundle was null");
        final List<EncryptionAlgoKeyLenPair> encryptList =
                PersistableBundleUtils.toList(encryptionBundle, EncryptionAlgoKeyLenPair::new);
        for (EncryptionAlgoKeyLenPair t : encryptList) {
            builder.addEncryptionAlgorithm(t.encryptionAlgo, t.keyLen);
        }

        final int[] integrityAlgoIdArray = in.getIntArray(INTEGRITY_ALGO_KEY);
        Objects.requireNonNull(integrityAlgoIdArray, "Integrity algo array was null");
        for (int algo : integrityAlgoIdArray) {
            builder.addIntegrityAlgorithm(algo);
        }

        final int[] dhGroupArray = in.getIntArray(DH_GROUP_KEY);
        Objects.requireNonNull(dhGroupArray, "DH Group array was null");
        for (int dh : dhGroupArray) {
            builder.addDhGroup(dh);
        }

        return builder.build();
    }
}
