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
import android.hardware.broadcastradio.ProgramListChunk;
import android.hardware.broadcastradio.VendorKeyValue;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;
import android.hardware.radio.UniqueProgramIdentifier;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.util.List;

final class AidlTestUtils {

    private AidlTestUtils() {
        throw new UnsupportedOperationException("AidlTestUtils class is noninstantiable");
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
        return new RadioManager.ProgramInfo(selector,
                logicallyTunedTo, physicallyTunedTo, /* relatedContents= */ null,
                /* infoFlags= */ 0, signalQuality,
                new RadioMetadata.Builder().build(), new ArrayMap<>());
    }

    static RadioManager.ProgramInfo makeProgramInfo(ProgramSelector selector, int signalQuality) {
        return makeProgramInfo(selector, selector.getPrimaryId(), selector.getPrimaryId(),
                signalQuality);
    }

    static ProgramIdentifier makeHalIdentifier(@IdentifierType int type, long value) {
        ProgramIdentifier halDabId = new ProgramIdentifier();
        halDabId.type = type;
        halDabId.value = value;
        return halDabId;
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

    static android.hardware.broadcastradio.ProgramSelector makeHalFmSelector(long freq) {
        ProgramIdentifier halId = makeHalIdentifier(IdentifierType.AMFM_FREQUENCY_KHZ, freq);
        return makeHalSelector(halId, /* secondaryIds= */ new ProgramIdentifier[0]);
    }

    static android.hardware.broadcastradio.ProgramSelector makeHalSelector(
            ProgramIdentifier primaryId, ProgramIdentifier[] secondaryIds) {
        android.hardware.broadcastradio.ProgramSelector hwSelector =
                new android.hardware.broadcastradio.ProgramSelector();
        hwSelector.primaryId = primaryId;
        hwSelector.secondaryIds = secondaryIds;
        return hwSelector;
    }

    static ProgramInfo makeHalProgramInfo(
            android.hardware.broadcastradio.ProgramSelector hwSel, int hwSignalQuality) {
        return makeHalProgramInfo(hwSel, hwSel.primaryId, hwSel.primaryId, hwSignalQuality);
    }

    static ProgramInfo programInfoToHalProgramInfo(RadioManager.ProgramInfo info) {
        return makeHalProgramInfo(
                ConversionUtils.programSelectorToHalProgramSelector(info.getSelector()),
                ConversionUtils.identifierToHalProgramIdentifier(info.getLogicallyTunedTo()),
                ConversionUtils.identifierToHalProgramIdentifier(info.getPhysicallyTunedTo()),
                info.getSignalStrength());
    }

    static ProgramInfo makeHalProgramInfo(
            android.hardware.broadcastradio.ProgramSelector hwSel,
            ProgramIdentifier logicallyTunedTo, ProgramIdentifier physicallyTunedTo,
            int hwSignalQuality) {
        ProgramInfo hwInfo = new ProgramInfo();
        hwInfo.selector = hwSel;
        hwInfo.logicallyTunedTo = logicallyTunedTo;
        hwInfo.physicallyTunedTo = physicallyTunedTo;
        hwInfo.signalQuality = hwSignalQuality;
        hwInfo.relatedContent = new ProgramIdentifier[]{};
        hwInfo.metadata = new Metadata[]{};
        return hwInfo;
    }

    static ProgramListChunk makeHalChunk(boolean purge, boolean complete,
            List<RadioManager.ProgramInfo> modified, List<ProgramSelector.Identifier> removed) {
        ProgramInfo[] halModified =
                new android.hardware.broadcastradio.ProgramInfo[modified.size()];
        for (int i = 0; i < modified.size(); i++) {
            halModified[i] = programInfoToHalProgramInfo(modified.get(i));
        }

        ProgramIdentifier[] halRemoved =
                new android.hardware.broadcastradio.ProgramIdentifier[removed.size()];
        for (int i = 0; i < removed.size(); i++) {
            halRemoved[i] = ConversionUtils.identifierToHalProgramIdentifier(removed.get(i));
        }
        return makeHalChunk(purge, complete, halModified, halRemoved);
    }

    static ProgramListChunk makeHalChunk(boolean purge, boolean complete,
            ProgramInfo[] modified, ProgramIdentifier[] removed) {
        ProgramListChunk halChunk = new ProgramListChunk();
        halChunk.purge = purge;
        halChunk.complete = complete;
        halChunk.modified = modified;
        halChunk.removed = removed;
        return halChunk;
    }

    static ProgramList.Chunk makeChunk(boolean purge, boolean complete,
            List<RadioManager.ProgramInfo> modified,
            List<UniqueProgramIdentifier> removed) throws RemoteException {
        ArraySet<RadioManager.ProgramInfo> modifiedSet = new ArraySet<>();
        if (modified != null) {
            modifiedSet.addAll(modified);
        }
        ArraySet<UniqueProgramIdentifier> removedSet = new ArraySet<>();
        if (removed != null) {
            removedSet.addAll(removed);
        }
        ProgramList.Chunk chunk = new ProgramList.Chunk(purge, complete, modifiedSet, removedSet);
        return chunk;
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
