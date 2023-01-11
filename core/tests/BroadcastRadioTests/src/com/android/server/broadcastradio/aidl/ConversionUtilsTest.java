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

import android.hardware.broadcastradio.AmFmBandRange;
import android.hardware.broadcastradio.AmFmRegionConfig;
import android.hardware.broadcastradio.DabTableEntry;
import android.hardware.broadcastradio.IdentifierType;
import android.hardware.broadcastradio.ProgramIdentifier;
import android.hardware.broadcastradio.ProgramInfo;
import android.hardware.broadcastradio.ProgramListChunk;
import android.hardware.broadcastradio.Properties;
import android.hardware.broadcastradio.VendorKeyValue;
import android.hardware.radio.Announcement;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.os.Build;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

public final class ConversionUtilsTest {

    private static final int FM_LOWER_LIMIT = 87_500;
    private static final int FM_UPPER_LIMIT = 108_000;
    private static final int FM_SPACING = 200;
    private static final int AM_LOWER_LIMIT = 540;
    private static final int AM_UPPER_LIMIT = 1_700;
    private static final int AM_SPACING = 10;
    private static final String DAB_ENTRY_LABEL_1 = "5A";
    private static final int DAB_ENTRY_FREQUENCY_1 = 174_928;
    private static final String DAB_ENTRY_LABEL_2 = "12D";
    private static final int DAB_ENTRY_FREQUENCY_2 = 229_072;
    private static final String VENDOR_INFO_KEY_1 = "vendorKey1";
    private static final String VENDOR_INFO_VALUE_1 = "vendorValue1";
    private static final String VENDOR_INFO_KEY_2 = "vendorKey2";
    private static final String VENDOR_INFO_VALUE_2 = "vendorValue2";
    private static final String TEST_SERVICE_NAME = "serviceMock";
    private static final int TEST_ID = 1;
    private static final String TEST_MAKER = "makerMock";
    private static final String TEST_PRODUCT = "productMock";
    private static final String TEST_VERSION = "versionMock";
    private static final String TEST_SERIAL = "serialMock";

    private static final int TEST_SIGNAL_QUALITY = 1;
    private static final long TEST_DAB_DMB_SID_EXT_VALUE = 0xA000000111L;
    private static final long TEST_DAB_ENSEMBLE_VALUE = 0x1001;
    private static final long TEST_DAB_FREQUENCY_VALUE = 220_352;
    private static final long TEST_FM_FREQUENCY_VALUE = 92_100;
    private static final long TEST_VENDOR_ID_VALUE = 9_901;

    private static final ProgramSelector.Identifier TEST_DAB_SID_EXT_ID =
            new ProgramSelector.Identifier(
                    ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT, TEST_DAB_DMB_SID_EXT_VALUE);
    private static final ProgramSelector.Identifier TEST_DAB_ENSEMBLE_ID =
            new ProgramSelector.Identifier(
                    ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE, TEST_DAB_ENSEMBLE_VALUE);
    private static final ProgramSelector.Identifier TEST_DAB_FREQUENCY_ID =
            new ProgramSelector.Identifier(
                    ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY, TEST_DAB_FREQUENCY_VALUE);
    private static final ProgramSelector.Identifier TEST_FM_FREQUENCY_ID =
            new ProgramSelector.Identifier(
                    ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, TEST_FM_FREQUENCY_VALUE);
    private static final ProgramSelector.Identifier TEST_VENDOR_ID =
            new ProgramSelector.Identifier(
                    ProgramSelector.IDENTIFIER_TYPE_VENDOR_START, TEST_VENDOR_ID_VALUE);

    private static final ProgramIdentifier TEST_HAL_DAB_SID_EXT_ID =
            AidlTestUtils.makeHalIdentifier(IdentifierType.DAB_SID_EXT, TEST_DAB_DMB_SID_EXT_VALUE);
    private static final ProgramIdentifier TEST_HAL_DAB_ENSEMBLE_ID =
            AidlTestUtils.makeHalIdentifier(IdentifierType.DAB_ENSEMBLE, TEST_DAB_ENSEMBLE_VALUE);
    private static final ProgramIdentifier TEST_HAL_DAB_FREQUENCY_ID =
            AidlTestUtils.makeHalIdentifier(IdentifierType.DAB_FREQUENCY_KHZ,
                    TEST_DAB_FREQUENCY_VALUE);
    private static final ProgramIdentifier TEST_HAL_FM_FREQUENCY_ID =
            AidlTestUtils.makeHalIdentifier(IdentifierType.AMFM_FREQUENCY_KHZ,
                    TEST_FM_FREQUENCY_VALUE);
    private static final ProgramIdentifier TEST_HAL_VENDOR_ID =
            AidlTestUtils.makeHalIdentifier(IdentifierType.VENDOR_START,
                    TEST_VENDOR_ID_VALUE);

    private static final ProgramSelector TEST_DAB_SELECTOR = new ProgramSelector(
            ProgramSelector.PROGRAM_TYPE_DAB, TEST_DAB_SID_EXT_ID,
            new ProgramSelector.Identifier[]{TEST_DAB_FREQUENCY_ID, TEST_DAB_ENSEMBLE_ID},
            /* vendorIds= */ null);
    private static final ProgramSelector TEST_FM_SELECTOR =
            AidlTestUtils.makeFmSelector(TEST_FM_FREQUENCY_VALUE);

    private static final int TEST_ENABLED_TYPE = Announcement.TYPE_EMERGENCY;
    private static final int TEST_ANNOUNCEMENT_FREQUENCY = FM_LOWER_LIMIT + FM_SPACING;

    private static final RadioManager.ModuleProperties MODULE_PROPERTIES =
            convertToModuleProperties();
    private static final Announcement ANNOUNCEMENT =
            ConversionUtils.announcementFromHalAnnouncement(
                    AidlTestUtils.makeAnnouncement(TEST_ENABLED_TYPE, TEST_ANNOUNCEMENT_FREQUENCY));

    @Rule
    public final Expect expect = Expect.create();

    @Test
    public void isAtLeastU_withTSdkVersion_returnsFalse() {
        expect.withMessage("Target SDK version of T")
                .that(ConversionUtils.isAtLeastU(Build.VERSION_CODES.TIRAMISU)).isFalse();
    }

    @Test
    public void isAtLeastU_withCurrentSdkVersion_returnsTrue() {
        expect.withMessage("Target SDK version of U")
                .that(ConversionUtils.isAtLeastU(Build.VERSION_CODES.CUR_DEVELOPMENT)).isTrue();
    }

    @Test
    public void propertiesFromHalProperties_idsMatch() {
        expect.withMessage("Properties id")
                .that(MODULE_PROPERTIES.getId()).isEqualTo(TEST_ID);
    }

    @Test
    public void propertiesFromHalProperties_serviceNamesMatch() {
        expect.withMessage("Service name")
                .that(MODULE_PROPERTIES.getServiceName()).isEqualTo(TEST_SERVICE_NAME);
    }

    @Test
    public void propertiesFromHalProperties_implementorsMatch() {
        expect.withMessage("Implementor")
                .that(MODULE_PROPERTIES.getImplementor()).isEqualTo(TEST_MAKER);
    }


    @Test
    public void propertiesFromHalProperties_productsMatch() {
        expect.withMessage("Product")
                .that(MODULE_PROPERTIES.getProduct()).isEqualTo(TEST_PRODUCT);
    }

    @Test
    public void propertiesFromHalProperties_versionsMatch() {
        expect.withMessage("Version")
                .that(MODULE_PROPERTIES.getVersion()).isEqualTo(TEST_VERSION);
    }

    @Test
    public void propertiesFromHalProperties_serialsMatch() {
        expect.withMessage("Serial")
                .that(MODULE_PROPERTIES.getSerial()).isEqualTo(TEST_SERIAL);
    }

    @Test
    public void propertiesFromHalProperties_dabTableInfoMatch() {
        Map<String, Integer> dabTableExpected = Map.of(DAB_ENTRY_LABEL_1, DAB_ENTRY_FREQUENCY_1,
                DAB_ENTRY_LABEL_2, DAB_ENTRY_FREQUENCY_2);

        expect.withMessage("Supported program types")
                .that(MODULE_PROPERTIES.getDabFrequencyTable())
                .containsExactlyEntriesIn(dabTableExpected);
    }

    @Test
    public void propertiesFromHalProperties_vendorInfoMatch() {
        Map<String, String> vendorInfoExpected = Map.of(VENDOR_INFO_KEY_1, VENDOR_INFO_VALUE_1,
                VENDOR_INFO_KEY_2, VENDOR_INFO_VALUE_2);

        expect.withMessage("Vendor info").that(MODULE_PROPERTIES.getVendorInfo())
                .containsExactlyEntriesIn(vendorInfoExpected);
    }

    @Test
    public void propertiesFromHalProperties_bandsMatch() {
        RadioManager.BandDescriptor[] bands = MODULE_PROPERTIES.getBands();

        expect.withMessage("Band descriptors").that(bands).hasLength(2);

        expect.withMessage("FM band frequency lower limit")
                .that(bands[0].getLowerLimit()).isEqualTo(FM_LOWER_LIMIT);
        expect.withMessage("FM band frequency upper limit")
                .that(bands[0].getUpperLimit()).isEqualTo(FM_UPPER_LIMIT);
        expect.withMessage("FM band frequency spacing")
                .that(bands[0].getSpacing()).isEqualTo(FM_SPACING);

        expect.withMessage("AM band frequency lower limit")
                .that(bands[1].getLowerLimit()).isEqualTo(AM_LOWER_LIMIT);
        expect.withMessage("AM band frequency upper limit")
                .that(bands[1].getUpperLimit()).isEqualTo(AM_UPPER_LIMIT);
        expect.withMessage("AM band frequency spacing")
                .that(bands[1].getSpacing()).isEqualTo(AM_SPACING);
    }

    @Test
    public void identifierToHalProgramIdentifier_withDabId() {
        ProgramIdentifier halDabId =
                ConversionUtils.identifierToHalProgramIdentifier(TEST_DAB_SID_EXT_ID);

        expect.withMessage("Converted HAL DAB identifier").that(halDabId)
                .isEqualTo(TEST_HAL_DAB_SID_EXT_ID);
    }

    @Test
    public void identifierFromHalProgramIdentifier_withDabId() {
        ProgramSelector.Identifier dabId =
                ConversionUtils.identifierFromHalProgramIdentifier(TEST_HAL_DAB_SID_EXT_ID);

        expect.withMessage("Converted DAB identifier").that(dabId).isEqualTo(TEST_DAB_SID_EXT_ID);
    }

    @Test
    public void programSelectorToHalProgramSelector_withValidSelector() {
        android.hardware.broadcastradio.ProgramSelector halDabSelector =
                ConversionUtils.programSelectorToHalProgramSelector(TEST_DAB_SELECTOR);

        expect.withMessage("Primary identifier of converted HAL DAB selector")
                .that(halDabSelector.primaryId).isEqualTo(TEST_HAL_DAB_SID_EXT_ID);
        expect.withMessage("Secondary identifiers of converted HAL DAB selector")
                .that(halDabSelector.secondaryIds).asList()
                .containsExactly(TEST_HAL_DAB_FREQUENCY_ID, TEST_HAL_DAB_ENSEMBLE_ID);
    }

    @Test
    public void programSelectorToHalProgramSelector_withInvalidDabSelector_returnsNull() {
        ProgramSelector invalidDbSelector = new ProgramSelector(ProgramSelector.PROGRAM_TYPE_DAB,
                TEST_DAB_SID_EXT_ID,
                new ProgramSelector.Identifier[0],
                new long[0]);

        android.hardware.broadcastradio.ProgramSelector invalidHalDabSelector =
                ConversionUtils.programSelectorToHalProgramSelector(invalidDbSelector);

        expect.withMessage("Invalid HAL DAB selector without required secondary ids")
                .that(invalidHalDabSelector).isNull();
    }

    @Test
    public void programSelectorFromHalProgramSelector_withValidSelector() {
        android.hardware.broadcastradio.ProgramSelector halDabSelector =
                AidlTestUtils.makeHalSelector(TEST_HAL_DAB_SID_EXT_ID, new ProgramIdentifier[]{
                        TEST_HAL_DAB_ENSEMBLE_ID, TEST_HAL_DAB_FREQUENCY_ID});

        ProgramSelector dabSelector =
                ConversionUtils.programSelectorFromHalProgramSelector(halDabSelector);

        expect.withMessage("Primary identifier of converted DAB selector")
                .that(dabSelector.getPrimaryId()).isEqualTo(TEST_DAB_SID_EXT_ID);
        expect.withMessage("Secondary identifiers of converted DAB selector")
                .that(dabSelector.getSecondaryIds()).asList()
                .containsExactly(TEST_DAB_FREQUENCY_ID, TEST_DAB_ENSEMBLE_ID);
    }

    @Test
    public void programSelectorFromHalProgramSelector_withInvalidSelector_returnsNull() {
        android.hardware.broadcastradio.ProgramSelector invalidHalDabSelector =
                AidlTestUtils.makeHalSelector(TEST_HAL_DAB_SID_EXT_ID, new ProgramIdentifier[]{});

        ProgramSelector invalidDabSelector =
                ConversionUtils.programSelectorFromHalProgramSelector(invalidHalDabSelector);

        expect.withMessage("Invalid DAB selector without required secondary ids")
                .that(invalidDabSelector).isNull();
    }

    @Test
    public void programInfoFromHalProgramInfo_withValidProgramInfo() {
        android.hardware.broadcastradio.ProgramSelector halDabSelector =
                AidlTestUtils.makeHalSelector(TEST_HAL_DAB_SID_EXT_ID, new ProgramIdentifier[]{
                        TEST_HAL_DAB_ENSEMBLE_ID, TEST_HAL_DAB_FREQUENCY_ID});
        ProgramInfo halProgramInfo = AidlTestUtils.makeHalProgramInfo(halDabSelector,
                TEST_HAL_DAB_SID_EXT_ID, TEST_HAL_DAB_FREQUENCY_ID, TEST_SIGNAL_QUALITY);

        RadioManager.ProgramInfo programInfo =
                ConversionUtils.programInfoFromHalProgramInfo(halProgramInfo);

        expect.withMessage("Primary id of selector of converted program info")
                .that(programInfo.getSelector().getPrimaryId()).isEqualTo(TEST_DAB_SID_EXT_ID);
        expect.withMessage("Secondary id of selector of converted program info")
                .that(programInfo.getSelector().getSecondaryIds()).asList()
                .containsExactly(TEST_DAB_ENSEMBLE_ID, TEST_DAB_FREQUENCY_ID);
        expect.withMessage("Logically tuned identifier of converted program info")
                .that(programInfo.getLogicallyTunedTo()).isEqualTo(TEST_DAB_SID_EXT_ID);
        expect.withMessage("Physically tuned identifier of converted program info")
                .that(programInfo.getPhysicallyTunedTo()).isEqualTo(TEST_DAB_FREQUENCY_ID);
        expect.withMessage("Signal quality of converted program info")
                .that(programInfo.getSignalStrength()).isEqualTo(TEST_SIGNAL_QUALITY);
    }

    @Test
    public void programInfoFromHalProgramInfo_withInvalidDabProgramInfo() {
        android.hardware.broadcastradio.ProgramSelector invalidHalDabSelector =
                AidlTestUtils.makeHalSelector(TEST_HAL_DAB_SID_EXT_ID,
                new ProgramIdentifier[]{TEST_HAL_DAB_ENSEMBLE_ID, TEST_HAL_DAB_FREQUENCY_ID});
        ProgramInfo halProgramInfo = AidlTestUtils.makeHalProgramInfo(invalidHalDabSelector,
                TEST_HAL_DAB_SID_EXT_ID, TEST_HAL_DAB_ENSEMBLE_ID, TEST_SIGNAL_QUALITY);

        RadioManager.ProgramInfo programInfo =
                ConversionUtils.programInfoFromHalProgramInfo(halProgramInfo);

        expect.withMessage("Invalid DAB program info with incorrect type of physically tuned to id")
                .that(programInfo).isNull();
    }

    @Test
    public void chunkFromHalProgramListChunk_withValidChunk() {
        boolean purge = false;
        boolean complete = true;
        android.hardware.broadcastradio.ProgramSelector halDabSelector =
                AidlTestUtils.makeHalSelector(TEST_HAL_DAB_SID_EXT_ID, new ProgramIdentifier[]{
                        TEST_HAL_DAB_ENSEMBLE_ID, TEST_HAL_DAB_FREQUENCY_ID});
        ProgramInfo halDabInfo = AidlTestUtils.makeHalProgramInfo(halDabSelector,
                TEST_HAL_DAB_SID_EXT_ID, TEST_HAL_DAB_FREQUENCY_ID, TEST_SIGNAL_QUALITY);
        RadioManager.ProgramInfo dabInfo =
                ConversionUtils.programInfoFromHalProgramInfo(halDabInfo);
        ProgramListChunk halChunk = AidlTestUtils.makeHalChunk(purge, complete,
                new ProgramInfo[]{halDabInfo},
                new ProgramIdentifier[]{TEST_HAL_VENDOR_ID, TEST_HAL_FM_FREQUENCY_ID});

        ProgramList.Chunk chunk = ConversionUtils.chunkFromHalProgramListChunk(halChunk);

        expect.withMessage("Purged state of the converted valid program list chunk")
                .that(chunk.isPurge()).isEqualTo(purge);
        expect.withMessage("Completion state of the converted valid program list chunk")
                .that(chunk.isComplete()).isEqualTo(complete);
        expect.withMessage("Modified program info in the converted valid program list chunk")
                .that(chunk.getModified()).containsExactly(dabInfo);
        expect.withMessage("Removed program ides in the converted valid program list chunk")
                .that(chunk.getRemoved()).containsExactly(TEST_VENDOR_ID, TEST_FM_FREQUENCY_ID);
    }

    @Test
    public void chunkFromHalProgramListChunk_withInvalidModifiedProgramInfo() {
        boolean purge = true;
        boolean complete = false;
        android.hardware.broadcastradio.ProgramSelector halDabSelector =
                AidlTestUtils.makeHalSelector(TEST_HAL_DAB_SID_EXT_ID, new ProgramIdentifier[]{
                        TEST_HAL_DAB_ENSEMBLE_ID, TEST_HAL_DAB_FREQUENCY_ID});
        ProgramInfo halDabInfo = AidlTestUtils.makeHalProgramInfo(halDabSelector,
                TEST_HAL_DAB_SID_EXT_ID, TEST_HAL_DAB_ENSEMBLE_ID, TEST_SIGNAL_QUALITY);
        ProgramListChunk halChunk = AidlTestUtils.makeHalChunk(purge, complete,
                new ProgramInfo[]{halDabInfo}, new ProgramIdentifier[]{TEST_HAL_FM_FREQUENCY_ID});

        ProgramList.Chunk chunk = ConversionUtils.chunkFromHalProgramListChunk(halChunk);

        expect.withMessage("Purged state of the converted invalid program list chunk")
                .that(chunk.isPurge()).isEqualTo(purge);
        expect.withMessage("Completion state of the converted invalid program list chunk")
                .that(chunk.isComplete()).isEqualTo(complete);
        expect.withMessage("Modified program info in the converted invalid program list chunk")
                .that(chunk.getModified()).isEmpty();
        expect.withMessage("Removed program ids in the converted invalid program list chunk")
                .that(chunk.getRemoved()).containsExactly(TEST_FM_FREQUENCY_ID);
    }

    @Test
    public void programSelectorMeetsSdkVersionRequirement_withLowerVersionId_returnsFalse() {
        expect.withMessage("Selector %s without required SDK version", TEST_DAB_SELECTOR)
                .that(ConversionUtils.programSelectorMeetsSdkVersionRequirement(TEST_DAB_SELECTOR,
                        Build.VERSION_CODES.TIRAMISU)).isFalse();
    }

    @Test
    public void programSelectorMeetsSdkVersionRequirement_withRequiredVersionId_returnsTrue() {
        expect.withMessage("Selector %s with required SDK version", TEST_FM_SELECTOR)
                .that(ConversionUtils.programSelectorMeetsSdkVersionRequirement(TEST_FM_SELECTOR,
                        Build.VERSION_CODES.TIRAMISU)).isTrue();
    }

    @Test
    public void programInfoMeetsSdkVersionRequirement_withLowerVersionId_returnsFalse() {
        RadioManager.ProgramInfo dabProgramInfo = AidlTestUtils.makeProgramInfo(TEST_DAB_SELECTOR,
                TEST_DAB_SID_EXT_ID, TEST_DAB_FREQUENCY_ID, TEST_SIGNAL_QUALITY);

        expect.withMessage("Program info %s without required SDK version", dabProgramInfo)
                .that(ConversionUtils.programInfoMeetsSdkVersionRequirement(dabProgramInfo,
                        Build.VERSION_CODES.TIRAMISU)).isFalse();
    }

    @Test
    public void programInfoMeetsSdkVersionRequirement_withRequiredVersionId_returnsTrue() {
        RadioManager.ProgramInfo fmProgramInfo = AidlTestUtils.makeProgramInfo(TEST_FM_SELECTOR,
                TEST_SIGNAL_QUALITY);

        expect.withMessage("Program info %s with required SDK version", fmProgramInfo)
                .that(ConversionUtils.programInfoMeetsSdkVersionRequirement(fmProgramInfo,
                        Build.VERSION_CODES.TIRAMISU)).isTrue();
    }

    @Test
    public void convertChunkToTargetSdkVersion_withLowerSdkVersion() {
        RadioManager.ProgramInfo dabProgramInfo = AidlTestUtils.makeProgramInfo(TEST_DAB_SELECTOR,
                TEST_DAB_SID_EXT_ID, TEST_DAB_FREQUENCY_ID, TEST_SIGNAL_QUALITY);
        RadioManager.ProgramInfo fmProgramInfo = AidlTestUtils.makeProgramInfo(TEST_FM_SELECTOR,
                TEST_SIGNAL_QUALITY);
        ProgramList.Chunk chunk = new ProgramList.Chunk(/* purge= */ true,
                /* complete= */ true, Set.of(dabProgramInfo, fmProgramInfo),
                Set.of(TEST_DAB_SID_EXT_ID, TEST_DAB_ENSEMBLE_ID, TEST_VENDOR_ID));

        ProgramList.Chunk convertedChunk = ConversionUtils.convertChunkToTargetSdkVersion(chunk,
                Build.VERSION_CODES.TIRAMISU);

        expect.withMessage(
                "Purged state of the converted program list chunk with lower SDK version")
                .that(convertedChunk.isPurge()).isEqualTo(chunk.isPurge());
        expect.withMessage(
                "Completion state of the converted program list chunk with lower SDK version")
                .that(convertedChunk.isComplete()).isEqualTo(chunk.isComplete());
        expect.withMessage(
                "Modified program info in the converted program list chunk with lower SDK version")
                .that(convertedChunk.getModified()).containsExactly(fmProgramInfo);
        expect.withMessage(
                "Removed program ids in the converted program list chunk with lower SDK version")
                .that(convertedChunk.getRemoved())
                .containsExactly(TEST_DAB_ENSEMBLE_ID, TEST_VENDOR_ID);
    }

    @Test
    public void convertChunkToTargetSdkVersion_withRequiredSdkVersion() {
        RadioManager.ProgramInfo dabProgramInfo = AidlTestUtils.makeProgramInfo(TEST_DAB_SELECTOR,
                TEST_DAB_SID_EXT_ID, TEST_DAB_FREQUENCY_ID, TEST_SIGNAL_QUALITY);
        RadioManager.ProgramInfo fmProgramInfo = AidlTestUtils.makeProgramInfo(TEST_FM_SELECTOR,
                TEST_SIGNAL_QUALITY);
        ProgramList.Chunk chunk = new ProgramList.Chunk(/* purge= */ true,
                /* complete= */ true, Set.of(dabProgramInfo, fmProgramInfo),
                Set.of(TEST_DAB_SID_EXT_ID, TEST_DAB_ENSEMBLE_ID, TEST_VENDOR_ID));

        ProgramList.Chunk convertedChunk = ConversionUtils.convertChunkToTargetSdkVersion(chunk,
                Build.VERSION_CODES.CUR_DEVELOPMENT);

        expect.withMessage("Converted program list chunk with required SDK version")
                .that(convertedChunk).isEqualTo(chunk);
    }

    @Test
    public void announcementFromHalAnnouncement_typesMatch() {
        expect.withMessage("Announcement type")
                .that(ANNOUNCEMENT.getType()).isEqualTo(TEST_ENABLED_TYPE);
    }

    @Test
    public void announcementFromHalAnnouncement_selectorsMatch() {
        ProgramSelector.Identifier primaryIdExpected = new ProgramSelector.Identifier(
                ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, TEST_ANNOUNCEMENT_FREQUENCY);

        ProgramSelector selector = ANNOUNCEMENT.getSelector();

        expect.withMessage("Primary id of announcement selector")
                .that(selector.getPrimaryId()).isEqualTo(primaryIdExpected);
        expect.withMessage("Secondary ids of announcement selector")
                .that(selector.getSecondaryIds()).isEmpty();
    }

    @Test
    public void announcementFromHalAnnouncement_VendorInfoMatch() {
        expect.withMessage("Announcement vendor info")
                .that(ANNOUNCEMENT.getVendorInfo()).isEmpty();
    }

    private static RadioManager.ModuleProperties convertToModuleProperties() {
        AmFmRegionConfig amFmConfig = createAmFmRegionConfig();
        DabTableEntry[] dabTableEntries = new DabTableEntry[]{
                createDabTableEntry(DAB_ENTRY_LABEL_1, DAB_ENTRY_FREQUENCY_1),
                createDabTableEntry(DAB_ENTRY_LABEL_2, DAB_ENTRY_FREQUENCY_2)};
        Properties properties = createHalProperties();

        return ConversionUtils.propertiesFromHalProperties(TEST_ID, TEST_SERVICE_NAME, properties,
                amFmConfig, dabTableEntries);
    }

    private static AmFmRegionConfig createAmFmRegionConfig() {
        AmFmRegionConfig amFmRegionConfig = new AmFmRegionConfig();
        amFmRegionConfig.ranges = new AmFmBandRange[]{
                createAmFmBandRange(FM_LOWER_LIMIT, FM_UPPER_LIMIT, FM_SPACING),
                createAmFmBandRange(AM_LOWER_LIMIT, AM_UPPER_LIMIT, AM_SPACING)};
        return amFmRegionConfig;
    }

    private static AmFmBandRange createAmFmBandRange(int lowerBound, int upperBound, int spacing) {
        AmFmBandRange bandRange = new AmFmBandRange();
        bandRange.lowerBound = lowerBound;
        bandRange.upperBound = upperBound;
        bandRange.spacing = spacing;
        bandRange.seekSpacing = bandRange.spacing;
        return bandRange;
    }

    private static DabTableEntry createDabTableEntry(String label, int value) {
        DabTableEntry dabTableEntry = new DabTableEntry();
        dabTableEntry.label = label;
        dabTableEntry.frequencyKhz = value;
        return dabTableEntry;
    }

    private static Properties createHalProperties() {
        Properties halProperties = new Properties();
        halProperties.supportedIdentifierTypes = new int[]{IdentifierType.AMFM_FREQUENCY_KHZ,
                IdentifierType.RDS_PI, IdentifierType.DAB_SID_EXT};
        halProperties.maker = TEST_MAKER;
        halProperties.product = TEST_PRODUCT;
        halProperties.version = TEST_VERSION;
        halProperties.serial = TEST_SERIAL;
        halProperties.vendorInfo = new VendorKeyValue[]{
                AidlTestUtils.makeVendorKeyValue(VENDOR_INFO_KEY_1, VENDOR_INFO_VALUE_1),
                AidlTestUtils.makeVendorKeyValue(VENDOR_INFO_KEY_2, VENDOR_INFO_VALUE_2)};
        return halProperties;
    }
}
