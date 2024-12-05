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
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.nfc.cardemulation.CardEmulation;

/**
 * Represents an Application ID (AID) entry in current routing table.
 * @hide
 */
@FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
@SystemApi
public class RoutingTableAidEntry extends NfcRoutingTableEntry {
    private final String mValue;

    /** @hide */
    public RoutingTableAidEntry(int nfceeId, String value,
            @CardEmulation.ProtocolAndTechnologyRoute int routeType) {
        super(nfceeId, TYPE_AID, routeType);
        this.mValue = value;
    }

    /**
     * Gets AID value.
     * @return String of AID
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @NonNull
    public String getAid() {
        return mValue;
    }
}
