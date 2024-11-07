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

/**
 * Represents a system code entry in current routing table.
 * @hide
 */
@FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
@SystemApi
public class RoutingTableSystemCodeEntry extends NfcRoutingTableEntry {
    private final byte[] mValue;

    /** @hide */
    public RoutingTableSystemCodeEntry(int nfceeId, byte[] value) {
        super(nfceeId);
        this.mValue = value;
    }

    /**
     * Gets system code value.
     * @return Byte array of system code
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @NonNull
    public byte[] getSystemCode() {
        return mValue;
    }
}
