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

import android.annotation.NonNull;
import android.net.ipsec.ike.SaProposal;
import android.os.PersistableBundle;
import android.util.Pair;

import com.android.server.vcn.util.PersistableBundleUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Abstract utility class to convert SaProposal to/from PersistableBundle.
 *
 * @hide
 */
abstract class SaProposalUtilsBase {
    static final String ENCRYPT_ALGO_KEY = "ENCRYPT_ALGO_KEY";
    static final String INTEGRITY_ALGO_KEY = "INTEGRITY_ALGO_KEY";
    static final String DH_GROUP_KEY = "DH_GROUP_KEY";

    static class EncryptionAlgoKeyLenPair {
        private static final String ALGO_KEY = "ALGO_KEY";
        private static final String KEY_LEN_KEY = "KEY_LEN_KEY";

        public final int encryptionAlgo;
        public final int keyLen;

        EncryptionAlgoKeyLenPair(int encryptionAlgo, int keyLen) {
            this.encryptionAlgo = encryptionAlgo;
            this.keyLen = keyLen;
        }

        EncryptionAlgoKeyLenPair(PersistableBundle in) {
            Objects.requireNonNull(in, "PersistableBundle was null");

            this.encryptionAlgo = in.getInt(ALGO_KEY);
            this.keyLen = in.getInt(KEY_LEN_KEY);
        }

        public PersistableBundle toPersistableBundle() {
            final PersistableBundle result = new PersistableBundle();

            result.putInt(ALGO_KEY, encryptionAlgo);
            result.putInt(KEY_LEN_KEY, keyLen);

            return result;
        }
    }

    /**
     * Serializes common info of a SaProposal to a PersistableBundle.
     *
     * @hide
     */
    @NonNull
    static PersistableBundle toPersistableBundle(SaProposal proposal) {
        final PersistableBundle result = new PersistableBundle();

        final List<EncryptionAlgoKeyLenPair> encryptAlgoKeyLenPairs = new ArrayList<>();
        for (Pair<Integer, Integer> pair : proposal.getEncryptionAlgorithms()) {
            encryptAlgoKeyLenPairs.add(new EncryptionAlgoKeyLenPair(pair.first, pair.second));
        }
        final PersistableBundle encryptionBundle =
                PersistableBundleUtils.fromList(
                        encryptAlgoKeyLenPairs, EncryptionAlgoKeyLenPair::toPersistableBundle);
        result.putPersistableBundle(ENCRYPT_ALGO_KEY, encryptionBundle);

        final int[] integrityAlgoIdArray =
                proposal.getIntegrityAlgorithms().stream().mapToInt(i -> i).toArray();
        result.putIntArray(INTEGRITY_ALGO_KEY, integrityAlgoIdArray);

        final int[] dhGroupArray = proposal.getDhGroups().stream().mapToInt(i -> i).toArray();
        result.putIntArray(DH_GROUP_KEY, dhGroupArray);

        return result;
    }
}
