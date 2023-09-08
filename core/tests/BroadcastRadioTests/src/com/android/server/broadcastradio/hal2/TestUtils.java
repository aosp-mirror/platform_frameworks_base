/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.broadcastradio.hal2;

import android.hardware.broadcastradio.V2_0.IdentifierType;
import android.hardware.broadcastradio.V2_0.ProgramIdentifier;
import android.hardware.broadcastradio.V2_0.ProgramInfo;
import android.hardware.broadcastradio.V2_0.VendorKeyValue;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;
import android.util.ArrayMap;

import java.util.ArrayList;

final class TestUtils {

    private TestUtils() {
        throw new UnsupportedOperationException("TestUtils class is noninstantiable");
    }

    static RadioManager.ModuleProperties makeDefaultModuleProperties() {
        return new RadioManager.ModuleProperties(
                /* id= */ 0, /* serviceName= */ "", /* classId= */ 0, /* implementor= */ "",
                /* product= */ "", /* version= */ "", /* serial= */ "", /* numTuners= */ 0,
                /* numAudioSources= */ 0, /* isInitializationRequired= */ false,
                /* isCaptureSupported= */ false, /* bands= */ null,
                /* isBgScanSupported= */ false, new int[] {}, new int[] {},
                /* dabFrequencyTable= */ null, /* vendorInfo= */ null);
    }

    static RadioManager.ProgramInfo makeProgramInfo(ProgramSelector selector,
            ProgramSelector.Identifier logicallyTunedTo,
            ProgramSelector.Identifier physicallyTunedTo, int signalQuality) {
        if (logicallyTunedTo == null) {
            logicallyTunedTo = selector.getPrimaryId();
        }
        if (physicallyTunedTo == null) {
            if (selector.getPrimaryId().getType()
                    == ProgramSelector.IDENTIFIER_TYPE_DAB_SID_EXT) {
                for (int i = 0; i < selector.getSecondaryIds().length; i++) {
                    if (selector.getSecondaryIds()[i].getType()
                            == ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY) {
                        physicallyTunedTo = selector.getSecondaryIds()[i];
                        break;
                    }
                }
            } else {
                physicallyTunedTo = selector.getPrimaryId();
            }
        }
        return new RadioManager.ProgramInfo(selector,
                logicallyTunedTo, physicallyTunedTo, /* relatedContents= */ null,
                /* infoFlags= */ 0, signalQuality,
                new RadioMetadata.Builder().build(), new ArrayMap<>());
    }

    static RadioManager.ProgramInfo makeProgramInfo(ProgramSelector selector, int signalQuality) {
        return makeProgramInfo(selector, /* logicallyTunedTo= */ null,
                /* physicallyTunedTo= */ null, signalQuality);
    }

    static ProgramSelector makeFmSelector(long freq) {
        return makeProgramSelector(ProgramSelector.PROGRAM_TYPE_FM,
                new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY,
                        freq));
    }

    static ProgramSelector makeProgramSelector(int programType,
            ProgramSelector.Identifier identifier) {
        return makeProgramSelector(programType, identifier, /* secondaryIds= */ null);
    }

    static ProgramSelector makeProgramSelector(int programType,
            ProgramSelector.Identifier primaryId, ProgramSelector.Identifier[] secondaryIds) {
        return new ProgramSelector(programType, primaryId, secondaryIds, /* vendorIds= */ null);
    }

    static ProgramInfo programInfoToHal(RadioManager.ProgramInfo info) {
        // Note that because Convert does not by design provide functions for all conversions, this
        // function only copies fields that are set by makeProgramInfo().
        ProgramInfo hwInfo = new ProgramInfo();
        hwInfo.selector = Convert.programSelectorToHal(info.getSelector());
        hwInfo.logicallyTunedTo = hwInfo.selector.primaryId;
        if (info.getSelector().getPrimaryId().getType()
                == ProgramSelector.IDENTIFIER_TYPE_DAB_SID_EXT) {
            for (int i = 0; i < info.getSelector().getSecondaryIds().length; i++) {
                if (info.getSelector().getSecondaryIds()[i].getType()
                        == ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY) {
                    hwInfo.physicallyTunedTo = Convert.programIdentifierToHal(info.getSelector()
                            .getSecondaryIds()[i]);
                    break;
                }
            }
        } else {
            hwInfo.physicallyTunedTo = Convert.programIdentifierToHal(info.getSelector()
                    .getPrimaryId());
        }
        hwInfo.signalQuality = info.getSignalStrength();
        return hwInfo;
    }

    static android.hardware.broadcastradio.V2_0.ProgramSelector makeHalFmSelector(int freq) {
        ProgramIdentifier halId = new ProgramIdentifier();
        halId.type = IdentifierType.AMFM_FREQUENCY;
        halId.value = freq;

        android.hardware.broadcastradio.V2_0.ProgramSelector halSelector =
                new android.hardware.broadcastradio.V2_0.ProgramSelector();
        halSelector.primaryId = halId;
        halSelector.secondaryIds = new ArrayList<ProgramIdentifier>();
        return halSelector;
    }

    static ProgramInfo makeHalProgramInfo(
            android.hardware.broadcastradio.V2_0.ProgramSelector hwSel, int hwSignalQuality) {
        ProgramInfo hwInfo = new ProgramInfo();
        hwInfo.selector = hwSel;
        hwInfo.logicallyTunedTo = hwSel.primaryId;
        hwInfo.physicallyTunedTo = hwSel.primaryId;
        hwInfo.signalQuality = hwSignalQuality;
        hwInfo.relatedContent = new ArrayList<>();
        hwInfo.metadata = new ArrayList<>();
        return hwInfo;
    }

    static VendorKeyValue makeVendorKeyValue(String vendorKey, String vendorValue) {
        VendorKeyValue vendorKeyValue = new VendorKeyValue();
        vendorKeyValue.key = vendorKey;
        vendorKeyValue.value = vendorValue;
        return vendorKeyValue;
    }

    static android.hardware.broadcastradio.V2_0.Announcement makeAnnouncement(int type,
            int selectorFreq) {
        android.hardware.broadcastradio.V2_0.Announcement halAnnouncement =
                new android.hardware.broadcastradio.V2_0.Announcement();
        halAnnouncement.type = (byte) type;
        halAnnouncement.selector = makeHalFmSelector(selectorFreq);
        halAnnouncement.vendorInfo = new ArrayList<>();
        return halAnnouncement;
    }
}
