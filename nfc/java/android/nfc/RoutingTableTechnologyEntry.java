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
 * Represents a technology entry in current routing table.
 * @hide
 */
@FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
@SystemApi
public class RoutingTableTechnologyEntry extends NfcRoutingTableEntry {
    /**
     * Technology-A.
     * <p>Tech-A is mostly used for payment and ticketing applications. It supports various
     * Tag platforms including Type 1, Type 2 and Type 4A tags. </p>
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int TECHNOLOGY_A = 0;
    /**
     * Technology-B which is based on ISO/IEC 14443-3 standard.
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int TECHNOLOGY_B = 1;
    /**
     * Technology-F.
     * <p>Tech-F is a standard which supports Type 3 Tags and NFC-DEP protocol etc.</p>
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int TECHNOLOGY_F = 2;
    /**
     * Technology-V.
     * <p>Tech-V is an NFC technology used for communication with passive tags that operate
     * at a longer range than other NFC technologies. </p>
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int TECHNOLOGY_V = 3;
    /**
     * Unsupported technology
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public static final int TECHNOLOGY_UNSUPPORTED = -1;

    /**
     *
     * @hide
     */
    @IntDef(prefix = { "TECHNOLOGY_" }, value = {
            TECHNOLOGY_A,
            TECHNOLOGY_B,
            TECHNOLOGY_F,
            TECHNOLOGY_V,
            TECHNOLOGY_UNSUPPORTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TechnologyValue{}

    private final @TechnologyValue int mValue;

    /** @hide */
    public RoutingTableTechnologyEntry(int nfceeId, @TechnologyValue int value,
            @CardEmulation.ProtocolAndTechnologyRoute int routeType) {
        super(nfceeId, TYPE_TECHNOLOGY, routeType);
        this.mValue = value;
    }

    /**
     * Gets technology value.
     * @return technology value
     */
    @TechnologyValue
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    public int getTechnology() {
        return mValue;
    }

    /** @hide */
    @TechnologyValue
    public static int techStringToInt(String tech) {
        return switch (tech) {
            case "TECHNOLOGY_A" -> TECHNOLOGY_A;
            case "TECHNOLOGY_B" -> TECHNOLOGY_B;
            case "TECHNOLOGY_F" -> TECHNOLOGY_F;
            case "TECHNOLOGY_V" -> TECHNOLOGY_V;
            default -> TECHNOLOGY_UNSUPPORTED;
        };
    }
}
