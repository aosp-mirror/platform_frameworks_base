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
import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.nfc.cardemulation.CardEmulation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a protocol entry in current routing table.
 * @hide
 */
@FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
@SystemApi
public class RoutingTableProtocolEntry extends NfcRoutingTableEntry {
    /**
     * Protocol undetermined.
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int PROTOCOL_UNDETERMINED = 0;
    /**
     * T1T Protocol
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int PROTOCOL_T1T = 1;
    /**
     * T2T Protocol
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int PROTOCOL_T2T = 2;
    /**
     * T3T Protocol
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int PROTOCOL_T3T = 3;
    /**
     * ISO-DEP Protocol
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int PROTOCOL_ISO_DEP = 4;
    /**
     * DEP Protocol
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int PROTOCOL_NFC_DEP = 5;
    /**
     * T5T Protocol
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int PROTOCOL_T5T = 6;
    /**
     * NDEF Protocol
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int PROTOCOL_NDEF = 7;
    /**
     * Unsupported Protocol
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int PROTOCOL_UNSUPPORTED = -1;

    /**
     *
     * @hide
     */
    @IntDef(prefix = { "PROTOCOL_" }, value = {
            PROTOCOL_UNDETERMINED,
            PROTOCOL_T1T,
            PROTOCOL_T2T,
            PROTOCOL_T3T,
            PROTOCOL_ISO_DEP,
            PROTOCOL_NFC_DEP,
            PROTOCOL_T5T,
            PROTOCOL_NDEF,
            PROTOCOL_UNSUPPORTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProtocolValue {}

    private final @ProtocolValue int mValue;

    /** @hide */
    public RoutingTableProtocolEntry(int nfceeId, @ProtocolValue int value,
            @CardEmulation.ProtocolAndTechnologyRoute int routeType) {
        super(nfceeId, TYPE_PROTOCOL, routeType);
        this.mValue = value;
    }

    /**
     * Gets Protocol value.
     * @return Protocol defined in {@link ProtocolValue}
     */
    @ProtocolValue
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public int getProtocol() {
        return mValue;
    }

    /** @hide */
    @ProtocolValue
    public static int protocolStringToInt(String protocolString) {
        return switch (protocolString) {
            case "PROTOCOL_T1T" -> PROTOCOL_T1T;
            case "PROTOCOL_T2T" -> PROTOCOL_T2T;
            case "PROTOCOL_T3T" -> PROTOCOL_T3T;
            case "PROTOCOL_ISO_DEP" -> PROTOCOL_ISO_DEP;
            case "PROTOCOL_NFC_DEP" -> PROTOCOL_NFC_DEP;
            case "PROTOCOL_T5T" -> PROTOCOL_T5T;
            case "PROTOCOL_NDEF" -> PROTOCOL_NDEF;
            case "PROTOCOL_UNDETERMINED" -> PROTOCOL_UNDETERMINED;
            default -> PROTOCOL_UNSUPPORTED;
        };
    }
}
