/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.nfc;


import android.annotation.FlaggedApi;
import android.annotation.SystemApi;

/**
 * Class to represent an entry of routing table. This class is abstract and extended by
 * {@link RoutingTableTechnologyEntry}, {@link RoutingTableProtocolEntry},
 * {@link RoutingTableAidEntry} and {@link RoutingTableSystemCodeEntry}.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
@SystemApi
public abstract class NfcRoutingTableEntry {
    private final int mNfceeId;

    /** @hide */
    protected NfcRoutingTableEntry(int nfceeId) {
        mNfceeId = nfceeId;
    }

    /**
     * Gets the NFCEE Id of this entry.
     * @return an integer of NFCEE Id.
     */
    public int getNfceeId() {
        return mNfceeId;
    }
}
