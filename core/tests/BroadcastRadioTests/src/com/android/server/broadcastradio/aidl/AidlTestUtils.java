/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.broadcastradio.aidl;

import android.hardware.broadcastradio.IdentifierType;
import android.hardware.broadcastradio.Metadata;
import android.hardware.broadcastradio.ProgramIdentifier;
import android.hardware.broadcastradio.ProgramInfo;
import android.hardware.broadcastradio.VendorKeyValue;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;
import android.util.ArrayMap;

final class AidlTestUtils {

    private AidlTestUtils() {
        throw new UnsupportedOperationException("AidlTestUtils class is noninstantiable");
    }

    static RadioManager.ProgramInfo makeProgramInfo(ProgramSelector selector, int signalQuality) {
        return new RadioManager.ProgramInfo(selector,
                selector.getPrimaryId(), selector.getPrimaryId(), /* relatedContents= */ null,
                /* infoFlags= */ 0, signalQuality,
                new RadioMetadata.Builder().build(), new ArrayMap<>());
    }

    static RadioManager.ProgramInfo makeProgramInfo(int programType,
            ProgramSelector.Identifier identifier, int signalQuality) {
        ProgramSelector selector = makeProgramSelector(programType, identifier);
        return makeProgramInfo(selector, signalQuality);
    }

    static ProgramSelector makeFmSelector(long freq) {
        return makeProgramSelector(ProgramSelector.PROGRAM_TYPE_FM,
                new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY,
                        freq));
    }

    static ProgramSelector makeProgramSelector(int programType,
            ProgramSelector.Identifier identifier) {
        return new ProgramSelector(programType, identifier, /* secondaryIds= */ null,
                /* vendorIds= */ null);
    }

    static android.hardware.broadcastradio.ProgramSelector makeHalFmSelector(int freq) {
        ProgramIdentifier halId = new ProgramIdentifier();
        halId.type = IdentifierType.AMFM_FREQUENCY_KHZ;
        halId.value = freq;

        android.hardware.broadcastradio.ProgramSelector halSelector =
                new android.hardware.broadcastradio.ProgramSelector();
        halSelector.primaryId = halId;
        halSelector.secondaryIds = new ProgramIdentifier[0];
        return halSelector;
    }

    static ProgramInfo programInfoToHalProgramInfo(RadioManager.ProgramInfo info) {
        // Note that because ConversionUtils does not by design provide functions for all
        // conversions, this function only copies fields that are set by makeProgramInfo().
        ProgramInfo hwInfo = new ProgramInfo();
        hwInfo.selector = ConversionUtils.programSelectorToHalProgramSelector(info.getSelector());
        hwInfo.logicallyTunedTo =
                ConversionUtils.identifierToHalProgramIdentifier(info.getLogicallyTunedTo());
        hwInfo.physicallyTunedTo =
                ConversionUtils.identifierToHalProgramIdentifier(info.getPhysicallyTunedTo());
        hwInfo.signalQuality = info.getSignalStrength();
        hwInfo.relatedContent = new ProgramIdentifier[]{};
        hwInfo.metadata = new Metadata[]{};
        return hwInfo;
    }

    static ProgramInfo makeHalProgramInfo(
            android.hardware.broadcastradio.ProgramSelector hwSel, int hwSignalQuality) {
        ProgramInfo hwInfo = new ProgramInfo();
        hwInfo.selector = hwSel;
        hwInfo.logicallyTunedTo = hwSel.primaryId;
        hwInfo.physicallyTunedTo = hwSel.primaryId;
        hwInfo.signalQuality = hwSignalQuality;
        hwInfo.relatedContent = new ProgramIdentifier[]{};
        hwInfo.metadata = new Metadata[]{};
        return hwInfo;
    }

    static VendorKeyValue makeVendorKeyValue(String vendorKey, String vendorValue) {
        VendorKeyValue vendorKeyValue = new VendorKeyValue();
        vendorKeyValue.key = vendorKey;
        vendorKeyValue.value = vendorValue;
        return vendorKeyValue;
    }

    static android.hardware.broadcastradio.Announcement makeAnnouncement(int type,
            int selectorFreq) {
        android.hardware.broadcastradio.Announcement halAnnouncement =
                new android.hardware.broadcastradio.Announcement();
        halAnnouncement.type = (byte) type;
        halAnnouncement.selector = makeHalFmSelector(selectorFreq);
        halAnnouncement.vendorInfo = new VendorKeyValue[]{};
        return halAnnouncement;
    }
}
