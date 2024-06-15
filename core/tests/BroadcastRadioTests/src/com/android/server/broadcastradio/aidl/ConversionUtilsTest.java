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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyLong;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;

import android.app.compat.CompatChanges;
import android.hardware.broadcastradio.AmFmBandRange;
import android.hardware.broadcastradio.AmFmRegionConfig;
import android.hardware.broadcastradio.ConfigFlag;
import android.hardware.broadcastradio.DabTableEntry;
import android.hardware.broadcastradio.IdentifierType;
import android.hardware.broadcastradio.Metadata;
import android.hardware.broadcastradio.ProgramFilter;
import android.hardware.broadcastradio.ProgramIdentifier;
import android.hardware.broadcastradio.ProgramInfo;
import android.hardware.broadcastradio.Properties;
import android.hardware.broadcastradio.Result;
import android.hardware.broadcastradio.VendorKeyValue;
import android.hardware.radio.Announcement;
import android.hardware.radio.Flags;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;
import android.hardware.radio.UniqueProgramIdentifier;
import android.os.ServiceSpecificException;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.server.broadcastradio.ExtendedRadioMockitoTestCase;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.stubbing.Answer;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConversionUtilsTest extends ExtendedRadioMockitoTestCase {

    private static final int T_APP_UID = 1001;
    private static final int U_APP_UID = 1002;
    private static final int V_APP_UID = 1003;

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
    private static final long TEST_DAB_SID_EXT_LEGACY_VALUE = 0xA00111L;
    private static final long TEST_DAB_ENSEMBLE_VALUE = 0x1001;
    private static final long TEST_DAB_FREQUENCY_VALUE = 220_352;
    private static final long TEST_FM_FREQUENCY_VALUE = 92_100;
    private static final long TEST_HD_FREQUENCY_VALUE = 95_300;
    private static final long TEST_HD_STATION_ID_EXT_VALUE = 0x100000001L
            | (TEST_HD_FREQUENCY_VALUE << 36);
    private static final long TEST_HD_LOCATION_VALUE =  0x4E647007665CF6L;
    private static final long TEST_VENDOR_ID_VALUE = 9_901;

    private static final ProgramSelector.Identifier TEST_INVALID_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_INVALID, 1);
    private static final ProgramIdentifier TEST_HAL_INVALID_ID =
            AidlTestUtils.makeHalIdentifier(IdentifierType.INVALID, 1);

    private static final ProgramSelector.Identifier TEST_DAB_SID_EXT_ID =
            new ProgramSelector.Identifier(
                    ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT, TEST_DAB_DMB_SID_EXT_VALUE);
    private static final ProgramSelector.Identifier TEST_DAB_SID_EXT_LEGACY_ID =
            new ProgramSelector.Identifier(
                    ProgramSelector.IDENTIFIER_TYPE_DAB_SID_EXT, TEST_DAB_SID_EXT_LEGACY_VALUE);
    private static final ProgramSelector.Identifier TEST_DAB_ENSEMBLE_ID =
            new ProgramSelector.Identifier(
                    ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE, TEST_DAB_ENSEMBLE_VALUE);
    private static final ProgramSelector.Identifier TEST_DAB_FREQUENCY_ID =
            new ProgramSelector.Identifier(
                    ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY, TEST_DAB_FREQUENCY_VALUE);
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

    private static final ProgramSelector TEST_DAB_SELECTOR = new ProgramSelector(
            ProgramSelector.PROGRAM_TYPE_DAB, TEST_DAB_SID_EXT_ID,
            new ProgramSelector.Identifier[]{TEST_DAB_FREQUENCY_ID, TEST_DAB_ENSEMBLE_ID},
            /* vendorIds= */ null);
    private static final ProgramSelector TEST_DAB_SELECTOR_LEGACY = new ProgramSelector(
            ProgramSelector.PROGRAM_TYPE_DAB, TEST_DAB_SID_EXT_LEGACY_ID,
            new ProgramSelector.Identifier[]{TEST_DAB_FREQUENCY_ID, TEST_DAB_ENSEMBLE_ID},
            /* vendorIds= */ null);
    private static final ProgramSelector TEST_FM_SELECTOR =
            AidlTestUtils.makeFmSelector(TEST_FM_FREQUENCY_VALUE);

    private static final ProgramSelector.Identifier TEST_HD_STATION_EXT_ID =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_HD_STATION_ID_EXT,
                    TEST_HD_STATION_ID_EXT_VALUE);

    private static final ProgramIdentifier TEST_HAL_HD_STATION_EXT_ID =
            AidlTestUtils.makeHalIdentifier(IdentifierType.HD_STATION_ID_EXT,
                    TEST_HD_STATION_ID_EXT_VALUE);
    private static final ProgramIdentifier TEST_HAL_HD_STATION_LOCATION_ID =
            AidlTestUtils.makeHalIdentifier(IdentifierType.HD_STATION_LOCATION,
                    TEST_HD_LOCATION_VALUE);

    private static final UniqueProgramIdentifier TEST_DAB_UNIQUE_ID = new UniqueProgramIdentifier(
            TEST_DAB_SELECTOR);

    private static final UniqueProgramIdentifier TEST_VENDOR_UNIQUE_ID =
            new UniqueProgramIdentifier(TEST_VENDOR_ID);

    private static final int TEST_ENABLED_TYPE = Announcement.TYPE_EMERGENCY;
    private static final int TEST_ANNOUNCEMENT_FREQUENCY = FM_LOWER_LIMIT + FM_SPACING;

    private static final RadioManager.ModuleProperties MODULE_PROPERTIES =
            createModuleProperties();
    private static final Announcement ANNOUNCEMENT =
            ConversionUtils.announcementFromHalAnnouncement(
                    AidlTestUtils.makeAnnouncement(TEST_ENABLED_TYPE, TEST_ANNOUNCEMENT_FREQUENCY));

    private static final String TEST_SONG_TITLE = "titleTest";
    private static final int TEST_ALBUM_ART = 2;
    private static final int TEST_HD_SUBCHANNELS = 1;

    private static final Metadata TEST_HAL_SONG_TITLE = Metadata.songTitle(TEST_SONG_TITLE);
    private static final Metadata TEST_HAL_ALBUM_ART = Metadata.albumArt(TEST_ALBUM_ART);
    private static final Metadata TEST_HAL_HD_SUBCHANNELS = Metadata.hdSubChannelsAvailable(
            TEST_HD_SUBCHANNELS);

    @Rule
    public final Expect expect = Expect.create();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Override
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        builder.spyStatic(CompatChanges.class);
    }

    @Before
    public void setUp() {
        doAnswer((Answer<Boolean>) invocationOnMock -> {
                    long changeId = invocationOnMock.getArgument(0);
                    int uid = invocationOnMock.getArgument(1);
                    if (uid == V_APP_UID) {
                        return changeId == ConversionUtils.RADIO_V_VERSION_REQUIRED
                                || changeId == ConversionUtils.RADIO_U_VERSION_REQUIRED;
                    } else if (uid == U_APP_UID) {
                        return changeId == ConversionUtils.RADIO_U_VERSION_REQUIRED;
                    }
                    return false;
                }
        ).when(() -> CompatChanges.isChangeEnabled(anyLong(), anyInt()));
    }

    @Test
    public void isAtLeastU_withLowerSdkVersion_returnsFalse() {
        expect.withMessage("Target SDK version of T")
                .that(ConversionUtils.isAtLeastU(T_APP_UID)).isFalse();
    }

    @Test
    public void isAtLeastU_withUSdkVersion_returnsTrue() {
        expect.withMessage("Target SDK version of U")
                .that(ConversionUtils.isAtLeastU(U_APP_UID)).isTrue();
    }

    @Test
    public void isAtLeastV_withLowerSdkVersion_returnsFalse() {
        expect.withMessage("Target SDK version U lower than V")
                .that(ConversionUtils.isAtLeastV(U_APP_UID)).isFalse();
    }

    @Test
    public void isAtLeastV_withVSdkVersion_returnsTrue() {
        expect.withMessage("Target SDK version of V not lower than V")
                .that(ConversionUtils.isAtLeastV(V_APP_UID)).isTrue();
    }

    @Test
    public void throwOnError_withCancelException() {
        ServiceSpecificException halException = new ServiceSpecificException(Result.CANCELED);

        RuntimeException thrown = ConversionUtils.throwOnError(halException, "tune");

        expect.withMessage("Exception thrown for canceling error").that(thrown)
                .hasMessageThat().contains("tune: CANCELED");
    }

    @Test
    public void throwOnError_withInvalidArgumentException() {
        ServiceSpecificException halException = new ServiceSpecificException(
                Result.INVALID_ARGUMENTS);

        RuntimeException thrown = ConversionUtils.throwOnError(halException, "tune");

        expect.withMessage("Exception thrown for invalid argument error")
                .that(thrown).hasMessageThat().contains("tune: INVALID_ARGUMENTS");
    }

    @Test
    public void throwOnError_withTimeoutException() {
        ServiceSpecificException halException = new ServiceSpecificException(Result.TIMEOUT);

        RuntimeException thrown = ConversionUtils.throwOnError(halException, "seek");

        expect.withMessage("Exception thrown for timeout error")
                .that(thrown).hasMessageThat().contains("seek: TIMEOUT");
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
    public void propertiesFromHalProperties_withoutAmFmAndDabConfigs() {
        RadioManager.ModuleProperties properties = createModuleProperties(/* amFmConfig= */ null,
                /* dabTableEntries= */ null);

        expect.withMessage("Empty AM/FM config")
                .that(properties.getBands()).asList().isEmpty();
        expect.withMessage("Empty DAB config")
                .that(properties.getDabFrequencyTable()).isNull();
    }

    @Test
    public void propertiesFromHalProperties_withInvalidBand() {
        AmFmRegionConfig amFmRegionConfig = new AmFmRegionConfig();
        amFmRegionConfig.ranges = new AmFmBandRange[]{createAmFmBandRange(/* lowerBound= */ 50000,
                /* upperBound= */ 60000, /* spacing= */ 10),
                createAmFmBandRange(FM_LOWER_LIMIT, FM_UPPER_LIMIT, FM_SPACING)};

        RadioManager.ModuleProperties properties = createModuleProperties(amFmRegionConfig,
                new DabTableEntry[]{});

        RadioManager.BandDescriptor[] bands = properties.getBands();
        expect.withMessage("Band descriptors").that(bands).hasLength(1);
        expect.withMessage("FM band frequency lower limit")
                .that(bands[0].getLowerLimit()).isEqualTo(FM_LOWER_LIMIT);
        expect.withMessage("FM band frequency upper limit")
                .that(bands[0].getUpperLimit()).isEqualTo(FM_UPPER_LIMIT);
        expect.withMessage("FM band frequency spacing")
                .that(bands[0].getSpacing()).isEqualTo(FM_SPACING);
    }

    @Test
    public void identifierToHalProgramIdentifier_withDabId() {
        ProgramIdentifier halDabId =
                ConversionUtils.identifierToHalProgramIdentifier(TEST_DAB_SID_EXT_ID);

        expect.withMessage("Converted HAL DAB identifier").that(halDabId)
                .isEqualTo(TEST_HAL_DAB_SID_EXT_ID);
    }

    @Test
    public void identifierToHalProgramIdentifier_withDeprecateDabId() {
        long value = 0x98765ABCDL;
        ProgramSelector.Identifier dabId = new ProgramSelector.Identifier(
                        ProgramSelector.IDENTIFIER_TYPE_DAB_SID_EXT, value);
        ProgramIdentifier halDabIdExpected = AidlTestUtils.makeHalIdentifier(
                IdentifierType.DAB_SID_EXT, 0x987650000ABCDL);

        ProgramIdentifier halDabId = ConversionUtils.identifierToHalProgramIdentifier(dabId);

        expect.withMessage("Converted 28-bit DAB identifier for HAL").that(halDabId)
                .isEqualTo(halDabIdExpected);
    }

    @Test
    public void identifierToHalProgramIdentifier_withFlagEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        ProgramSelector.Identifier hdLocationId = createHdStationLocationIdWithFlagEnabled();

        ProgramIdentifier halHdLocationId =
                ConversionUtils.identifierToHalProgramIdentifier(hdLocationId);

        expect.withMessage("Converted HD location identifier for HAL").that(halHdLocationId)
                .isEqualTo(TEST_HAL_HD_STATION_LOCATION_ID);
    }

    @Test
    public void identifierFromHalProgramIdentifier_withDabId() {
        ProgramSelector.Identifier dabId =
                ConversionUtils.identifierFromHalProgramIdentifier(TEST_HAL_DAB_SID_EXT_ID);

        expect.withMessage("Converted DAB identifier").that(dabId).isEqualTo(TEST_DAB_SID_EXT_ID);
    }

    @Test
    public void identifierFromHalProgramIdentifier_withFlagEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        ProgramSelector.Identifier hdLocationIdExpected =
                createHdStationLocationIdWithFlagEnabled();

        ProgramSelector.Identifier hdLocationId =
                ConversionUtils.identifierFromHalProgramIdentifier(TEST_HAL_HD_STATION_LOCATION_ID);

        expect.withMessage("Converted HD location identifier").that(hdLocationId)
                .isEqualTo(hdLocationIdExpected);
    }

    @Test
    public void identifierFromHalProgramIdentifier_withFlagDisabled_returnsNull() {
        mSetFlagsRule.disableFlags(Flags.FLAG_HD_RADIO_IMPROVED);

        ProgramSelector.Identifier hdLocationId =
                ConversionUtils.identifierFromHalProgramIdentifier(TEST_HAL_HD_STATION_LOCATION_ID);

        expect.withMessage("Null HD location identifier with feature flag disabled")
                .that(hdLocationId).isNull();
    }

    @Test
    public void identifierFromHalProgramIdentifier_withInvalidIdentifier() {
        expect.withMessage("Identifier converted from invalid HAL identifier")
                .that(ConversionUtils.identifierFromHalProgramIdentifier(TEST_HAL_INVALID_ID))
                .isNull();
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
    public void programSelectorToHalProgramSelector_withInvalidSecondaryId() {
        ProgramSelector dabSelector = new ProgramSelector(ProgramSelector.PROGRAM_TYPE_DAB,
                TEST_DAB_SID_EXT_ID, new ProgramSelector.Identifier[]{TEST_INVALID_ID,
                    TEST_DAB_FREQUENCY_ID, TEST_DAB_ENSEMBLE_ID}, /* vendorIds= */ null);

        android.hardware.broadcastradio.ProgramSelector halDabSelector =
                ConversionUtils.programSelectorToHalProgramSelector(dabSelector);

        expect.withMessage("Primary identifier of converted HAL DAB selector with invalid "
                        + "secondary id").that(halDabSelector.primaryId)
                .isEqualTo(TEST_HAL_DAB_SID_EXT_ID);
        expect.withMessage("Secondary identifiers of converted HAL DAB selector with "
                        + "invalid secondary id").that(halDabSelector.secondaryIds).asList()
                .containsExactly(TEST_HAL_DAB_FREQUENCY_ID, TEST_HAL_DAB_ENSEMBLE_ID);
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
    public void programSelectorFromHalProgramSelector_withInvalidSelector() {
        android.hardware.broadcastradio.ProgramSelector invalidSelector =
                AidlTestUtils.makeHalSelector(TEST_HAL_INVALID_ID, new ProgramIdentifier[]{});

        expect.withMessage("Selector converted from invalid HAL selector")
                .that(ConversionUtils.programSelectorFromHalProgramSelector(invalidSelector))
                .isNull();
    }

    @Test
    public void programSelectorFromHalProgramSelector_withInvalidSecondaryId() {
        android.hardware.broadcastradio.ProgramSelector halDabSelector =
                AidlTestUtils.makeHalSelector(TEST_HAL_DAB_SID_EXT_ID, new ProgramIdentifier[]{
                        TEST_HAL_INVALID_ID, TEST_HAL_DAB_ENSEMBLE_ID, TEST_HAL_DAB_FREQUENCY_ID});

        ProgramSelector dabSelector =
                ConversionUtils.programSelectorFromHalProgramSelector(halDabSelector);

        expect.withMessage("Primary identifier of converted DAB selector with invalid "
                        + "secondary id").that(dabSelector.getPrimaryId())
                .isEqualTo(TEST_DAB_SID_EXT_ID);
        expect.withMessage("Secondary identifiers of converted DAB selector with invalid "
                        + "secondary id").that(dabSelector.getSecondaryIds()).asList()
                .containsExactly(TEST_DAB_FREQUENCY_ID, TEST_DAB_ENSEMBLE_ID);
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
    public void programInfoFromHalProgramInfo_withRelatedContent() {
        android.hardware.broadcastradio.ProgramSelector halDabSelector =
                AidlTestUtils.makeHalSelector(TEST_HAL_DAB_SID_EXT_ID, new ProgramIdentifier[]{
                        TEST_HAL_DAB_ENSEMBLE_ID, TEST_HAL_DAB_FREQUENCY_ID});
        ProgramInfo halProgramInfo = AidlTestUtils.makeHalProgramInfo(halDabSelector,
                TEST_HAL_DAB_SID_EXT_ID, TEST_HAL_DAB_FREQUENCY_ID, TEST_SIGNAL_QUALITY,
                new ProgramIdentifier[]{TEST_HAL_HD_STATION_EXT_ID}, new Metadata[]{});

        RadioManager.ProgramInfo programInfo =
                ConversionUtils.programInfoFromHalProgramInfo(halProgramInfo);

        expect.withMessage("Related content of converted program info")
                .that(programInfo.getRelatedContent()).containsExactly(TEST_HD_STATION_EXT_ID);
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
    public void programSelectorMeetsSdkVersionRequirement_withLowerVersionPrimaryId_returnsFalse() {
        expect.withMessage("Selector %s with primary id requiring higher-version SDK version",
                        TEST_DAB_SELECTOR).that(ConversionUtils
                .programSelectorMeetsSdkVersionRequirement(TEST_DAB_SELECTOR, T_APP_UID)).isFalse();
    }

    @Test
    public void programSelectorMeetsSdkVersionRequirement_withLowerVersionSecondaryId() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        ProgramSelector hdSelector = createHdSelectorWithFlagEnabled();

        expect.withMessage("Selector %s with secondary id requiring higher-version SDK version",
                hdSelector).that(ConversionUtils.programSelectorMeetsSdkVersionRequirement(
                        hdSelector, U_APP_UID)).isFalse();
    }

    @Test
    public void programSelectorMeetsSdkVersionRequirement_withRequiredVersionId_returnsTrue() {
        expect.withMessage("Selector %s with required SDK version", TEST_FM_SELECTOR)
                .that(ConversionUtils.programSelectorMeetsSdkVersionRequirement(TEST_FM_SELECTOR,
                        T_APP_UID)).isTrue();
    }

    @Test
    public void programSelectorMeetsSdkVersionRequirement_withRequiredVersionAndFlagEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        ProgramSelector hdSelector = createHdSelectorWithFlagEnabled();

        expect.withMessage("Selector %s with required SDK version and feature flag enabled",
                hdSelector).that(ConversionUtils.programSelectorMeetsSdkVersionRequirement(
                        hdSelector, V_APP_UID)).isTrue();
    }

    @Test
    public void programInfoMeetsSdkVersionRequirement_withLowerVersionId_returnsFalse() {
        RadioManager.ProgramInfo dabProgramInfo = AidlTestUtils.makeProgramInfo(TEST_DAB_SELECTOR,
                TEST_DAB_SID_EXT_ID, TEST_DAB_FREQUENCY_ID, TEST_SIGNAL_QUALITY);

        expect.withMessage("Program info %s without required SDK version", dabProgramInfo)
                .that(ConversionUtils.programInfoMeetsSdkVersionRequirement(dabProgramInfo,
                        T_APP_UID)).isFalse();
    }

    @Test
    public void programInfoMeetsSdkVersionRequirement_withLowerVersionIdForLogicallyTunedTo() {
        RadioManager.ProgramInfo dabProgramInfo = AidlTestUtils.makeProgramInfo(
                TEST_DAB_SELECTOR_LEGACY, TEST_DAB_SID_EXT_ID, TEST_DAB_FREQUENCY_ID,
                TEST_SIGNAL_QUALITY);

        expect.withMessage("Program info %s with logically tuned to ID not of required SDK version",
                        dabProgramInfo).that(ConversionUtils.programInfoMeetsSdkVersionRequirement(
                                dabProgramInfo, T_APP_UID)).isFalse();
    }

    @Test
    public void programInfoMeetsSdkVersionRequirement_withLowerVersionIdForRelatedContent() {
        RadioManager.ProgramInfo dabProgramInfo = new RadioManager.ProgramInfo(
                TEST_DAB_SELECTOR_LEGACY, TEST_DAB_SID_EXT_ID, TEST_DAB_FREQUENCY_ID,
                List.of(TEST_DAB_SID_EXT_ID), /* infoFlags= */ 0, TEST_SIGNAL_QUALITY,
                new RadioMetadata.Builder().build(), new ArrayMap<>());

        expect.withMessage("Program info %s with related content not of required SDK version",
                dabProgramInfo).that(ConversionUtils.programInfoMeetsSdkVersionRequirement(
                dabProgramInfo, T_APP_UID)).isFalse();
    }

    @Test
    public void programInfoMeetsSdkVersionRequirement_withRequiredVersionId_returnsTrue() {
        RadioManager.ProgramInfo fmProgramInfo = AidlTestUtils.makeProgramInfo(TEST_FM_SELECTOR,
                TEST_SIGNAL_QUALITY);

        expect.withMessage("Program info %s with required SDK version", fmProgramInfo)
                .that(ConversionUtils.programInfoMeetsSdkVersionRequirement(fmProgramInfo,
                        T_APP_UID)).isTrue();
    }

    @Test
    public void convertChunkToTargetSdkVersion_withLowerSdkVersion() {
        RadioManager.ProgramInfo dabProgramInfo = AidlTestUtils.makeProgramInfo(TEST_DAB_SELECTOR,
                TEST_DAB_SID_EXT_ID, TEST_DAB_FREQUENCY_ID, TEST_SIGNAL_QUALITY);
        RadioManager.ProgramInfo fmProgramInfo = AidlTestUtils.makeProgramInfo(TEST_FM_SELECTOR,
                TEST_SIGNAL_QUALITY);
        ProgramList.Chunk chunk = new ProgramList.Chunk(/* purge= */ true,
                /* complete= */ true, Set.of(dabProgramInfo, fmProgramInfo),
                Set.of(TEST_DAB_UNIQUE_ID, TEST_VENDOR_UNIQUE_ID));

        ProgramList.Chunk convertedChunk = ConversionUtils.convertChunkToTargetSdkVersion(chunk,
                T_APP_UID);

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
                .that(convertedChunk.getRemoved()).containsExactly(TEST_VENDOR_UNIQUE_ID);
    }

    @Test
    public void convertChunkToTargetSdkVersion_withRequiredSdkVersion() {
        RadioManager.ProgramInfo dabProgramInfo = AidlTestUtils.makeProgramInfo(TEST_DAB_SELECTOR,
                TEST_DAB_SID_EXT_ID, TEST_DAB_FREQUENCY_ID, TEST_SIGNAL_QUALITY);
        RadioManager.ProgramInfo fmProgramInfo = AidlTestUtils.makeProgramInfo(TEST_FM_SELECTOR,
                TEST_SIGNAL_QUALITY);
        ProgramList.Chunk chunk = new ProgramList.Chunk(/* purge= */ true,
                /* complete= */ true, Set.of(dabProgramInfo, fmProgramInfo),
                Set.of(TEST_DAB_UNIQUE_ID, TEST_VENDOR_UNIQUE_ID));

        ProgramList.Chunk convertedChunk = ConversionUtils.convertChunkToTargetSdkVersion(chunk,
                U_APP_UID);

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

    @Test
    public void configFlagMeetsSdkVersionRequirement_withRequiredSdkVersionAndFlagEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        int halForceAmAnalogFlag = ConfigFlag.FORCE_ANALOG_FM;

        expect.withMessage("Force Analog FM flag with required SDK version and feature flag"
                        + " enabled").that(ConversionUtils.configFlagMeetsSdkVersionRequirement(
                                halForceAmAnalogFlag, V_APP_UID)).isTrue();
    }

    @Test
    public void configFlagMeetsSdkVersionRequirement_withRequiredSdkVersionAndFlagDisabled() {
        mSetFlagsRule.disableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        int halForceAmAnalogFlag = ConfigFlag.FORCE_ANALOG_FM;

        expect.withMessage("Force Analog FM with required SDK version and with feature flag"
                        + " disabled").that(ConversionUtils.configFlagMeetsSdkVersionRequirement(
                                halForceAmAnalogFlag, V_APP_UID)).isFalse();
    }

    @Test
    public void configFlagMeetsSdkVersionRequirement_withLowerSdkVersion() {
        int halForceAmAnalogFlag = ConfigFlag.FORCE_ANALOG_FM;

        expect.withMessage("Force Analog FM without required SDK version")
                .that(ConversionUtils.configFlagMeetsSdkVersionRequirement(halForceAmAnalogFlag,
                        U_APP_UID)).isFalse();
    }

    @Test
    public void configFlagMeetsSdkVersionRequirement_withFConfigFlagWithoutSdkVersionRequired() {
        int halForceAmAnalogFlag = ConfigFlag.FORCE_DIGITAL;

        expect.withMessage("Force digital config flag")
                .that(ConversionUtils.configFlagMeetsSdkVersionRequirement(halForceAmAnalogFlag,
                        T_APP_UID)).isTrue();
    }

    @Test
    public void radioMetadataFromHalMetadata() {
        int rdsPtyValue = 3;
        int rbdsPtyValue = 4;
        String rdsRtValue = "rdsRtTest";
        String songAlbumValue = "songAlbumTest";
        String programNameValue = "programNameTest";
        RadioMetadata convertedMetadata = ConversionUtils.radioMetadataFromHalMetadata(
                new Metadata[]{TEST_HAL_SONG_TITLE, TEST_HAL_ALBUM_ART,
                        Metadata.rdsPty(rdsPtyValue), Metadata.rbdsPty(rbdsPtyValue),
                        Metadata.rdsRt(rdsRtValue), Metadata.songAlbum(songAlbumValue),
                        Metadata.programName(programNameValue)});

        expect.withMessage("Song title with flag enabled")
                .that(convertedMetadata.getString(RadioMetadata.METADATA_KEY_TITLE))
                .isEqualTo(TEST_SONG_TITLE);
        expect.withMessage("Album art with flag enabled")
                .that(convertedMetadata.getInt(RadioMetadata.METADATA_KEY_ART))
                .isEqualTo(TEST_ALBUM_ART);
        expect.withMessage("RDS PTY with flag enabled")
                .that(convertedMetadata.getInt(RadioMetadata.METADATA_KEY_RDS_PTY))
                .isEqualTo(rdsPtyValue);
        expect.withMessage("RBDS PTY with flag enabled")
                .that(convertedMetadata.getInt(RadioMetadata.METADATA_KEY_RBDS_PTY))
                .isEqualTo(rbdsPtyValue);
        expect.withMessage("RDS RT with flag enabled")
                .that(convertedMetadata.getString(RadioMetadata.METADATA_KEY_RDS_RT))
                .isEqualTo(rdsRtValue);
        expect.withMessage("Album with flag enabled")
                .that(convertedMetadata.getString(RadioMetadata.METADATA_KEY_ALBUM))
                .isEqualTo(songAlbumValue);
        expect.withMessage("Program name with flag enabled")
                .that(convertedMetadata.getString(RadioMetadata.METADATA_KEY_PROGRAM_NAME))
                .isEqualTo(programNameValue);
    }

    @Test
    public void radioMetadataFromHalMetadata_withDabMetadata() {
        String dabEnsembleNameValue = "dabEnsembleNameTest";
        String dabEnsembleNameShortValue = "dabEnsembleNameShortTest";
        String dabServiceNameValue = "dabServiceNameTest";
        String dabServiceNameShortValue = "dabServiceNameShortTest";
        String dabComponentNameValue = "dabComponentNameTest";
        String dabComponentNameShortValue = "dabComponentNameShortTest";
        RadioMetadata convertedMetadata = ConversionUtils.radioMetadataFromHalMetadata(
                new Metadata[]{Metadata.dabEnsembleName(dabEnsembleNameValue),
                        Metadata.dabEnsembleNameShort(dabEnsembleNameShortValue),
                        Metadata.dabServiceName(dabServiceNameValue),
                        Metadata.dabServiceNameShort(dabServiceNameShortValue),
                        Metadata.dabComponentName(dabComponentNameValue),
                        Metadata.dabComponentNameShort(dabComponentNameShortValue)});

        expect.withMessage("DAB Ensemble name with flag enabled")
                .that(convertedMetadata.getString(
                        RadioMetadata.METADATA_KEY_DAB_ENSEMBLE_NAME))
                .isEqualTo(dabEnsembleNameValue);
        expect.withMessage("DAB Ensemble short name with flag enabled")
                .that(convertedMetadata.getString(
                        RadioMetadata.METADATA_KEY_DAB_ENSEMBLE_NAME_SHORT))
                .isEqualTo(dabEnsembleNameShortValue);
        expect.withMessage("DAB service service name with flag enabled")
                .that(convertedMetadata.getString(RadioMetadata.METADATA_KEY_DAB_SERVICE_NAME))
                .isEqualTo(dabServiceNameValue);
        expect.withMessage("DAB service service short name with flag enabled")
                .that(convertedMetadata.getString(
                        RadioMetadata.METADATA_KEY_DAB_SERVICE_NAME_SHORT))
                .isEqualTo(dabServiceNameShortValue);
        expect.withMessage("DAB component name with flag enabled")
                .that(convertedMetadata.getString(RadioMetadata.METADATA_KEY_DAB_COMPONENT_NAME))
                .isEqualTo(dabComponentNameValue);
        expect.withMessage("DAB component short name with flag enabled")
                .that(convertedMetadata.getString(
                        RadioMetadata.METADATA_KEY_DAB_COMPONENT_NAME_SHORT))
                .isEqualTo(dabComponentNameShortValue);
    }

    @Test
    public void radioMetadataFromHalMetadata_withHdMedatadataAndFlagEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);
        String genreValue = "genreTest";
        String commentShortDescriptionValue = "commentShortDescriptionTest";
        String commentActualTextValue = "commentActualTextTest";
        String commercialValue = "commercialTest";
        List<String> ufidsValue = List.of("ufids1Test", "ufids2Test");
        String hdStationNameShortValue = "hdStationNameShortTest";
        String hdStationNameLongValue = "hdStationNameLongTest";
        RadioMetadata convertedMetadata = ConversionUtils.radioMetadataFromHalMetadata(
                new Metadata[]{TEST_HAL_HD_SUBCHANNELS, Metadata.genre(genreValue),
                        Metadata.commentShortDescription(commentShortDescriptionValue),
                        Metadata.commentActualText(commentActualTextValue),
                        Metadata.commercial(commercialValue),
                        Metadata.ufids(ufidsValue.toArray(new String[0])),
                        Metadata.hdStationNameShort(hdStationNameShortValue),
                        Metadata.hdStationNameLong(hdStationNameLongValue)});

        expect.withMessage("Genre with flag enabled")
                .that(convertedMetadata.getString(RadioMetadata.METADATA_KEY_GENRE))
                .isEqualTo(genreValue);
        expect.withMessage("Short description of comment with flag enabled")
                .that(convertedMetadata.getString(
                        RadioMetadata.METADATA_KEY_COMMENT_SHORT_DESCRIPTION))
                .isEqualTo(commentShortDescriptionValue);
        expect.withMessage("Actual text of comment with flag enabled")
                .that(convertedMetadata.getString(RadioMetadata.METADATA_KEY_COMMENT_ACTUAL_TEXT))
                .isEqualTo(commentActualTextValue);
        expect.withMessage("Commercial with flag enabled")
                .that(convertedMetadata.getString(RadioMetadata.METADATA_KEY_COMMERCIAL))
                .isEqualTo(commercialValue);
        expect.withMessage("UFIDs with flag enabled")
                .that(convertedMetadata.getStringArray(RadioMetadata.METADATA_KEY_UFIDS)).asList()
                .containsExactlyElementsIn(ufidsValue);
        expect.withMessage("HD station short name with flag enabled")
                .that(convertedMetadata.getString(RadioMetadata.METADATA_KEY_HD_STATION_NAME_SHORT))
                .isEqualTo(hdStationNameShortValue);
        expect.withMessage("HD station long name with flag enabled")
                .that(convertedMetadata.getString(RadioMetadata.METADATA_KEY_HD_STATION_NAME_LONG))
                .isEqualTo(hdStationNameLongValue);
        expect.withMessage("HD sub-channels with flag enabled")
                .that(convertedMetadata.getInt(RadioMetadata
                        .METADATA_KEY_HD_SUBCHANNELS_AVAILABLE)).isEqualTo(TEST_HD_SUBCHANNELS);
    }

    @Test
    public void radioMetadataFromHalMetadata_withFlagDisabled() {
        mSetFlagsRule.disableFlags(Flags.FLAG_HD_RADIO_IMPROVED);

        RadioMetadata convertedMetadata = ConversionUtils.radioMetadataFromHalMetadata(
                new Metadata[]{TEST_HAL_SONG_TITLE, TEST_HAL_HD_SUBCHANNELS, TEST_HAL_ALBUM_ART});

        expect.withMessage("Metadata with flag disabled").that(convertedMetadata.size())
                .isEqualTo(2);
        expect.withMessage("Song title with flag disabled")
                .that(convertedMetadata.getString(RadioMetadata.METADATA_KEY_TITLE))
                .isEqualTo(TEST_SONG_TITLE);
        expect.withMessage("Album art with flag disabled")
                .that(convertedMetadata.getInt(RadioMetadata.METADATA_KEY_ART))
                .isEqualTo(TEST_ALBUM_ART);
    }

    @Test
    public void getBands_withInvalidFrequency() {
        expect.withMessage("Band for invalid frequency")
                .that(Utils.getBand(/* freq= */ 110000)).isEqualTo(Utils.FrequencyBand.UNKNOWN);
    }

    @Test
    public void filterToHalProgramFilter_withNullFilter() {
        ProgramFilter filter = ConversionUtils.filterToHalProgramFilter(null);

        expect.withMessage("Filter identifier types").that(filter.identifierTypes)
                .asList().isEmpty();
        expect.withMessage("Filter identifiers").that(filter.identifiers).asList()
                .isEmpty();
    }

    @Test
    public void filterToHalProgramFilter_withInvalidIdentifier() {
        Set<ProgramSelector.Identifier> identifiers =
                new ArraySet<ProgramSelector.Identifier>(2);
        identifiers.add(TEST_INVALID_ID);
        identifiers.add(TEST_DAB_SID_EXT_ID);
        ProgramList.Filter filter = new ProgramList.Filter(/* identifierTypes */ new ArraySet<>(),
                identifiers, /* includeCategories= */ true, /* excludeModifications= */ false);
        ProgramFilter halFilter = ConversionUtils.filterToHalProgramFilter(filter);

        expect.withMessage("Filter identifiers with invalid ones removed")
                .that(halFilter.identifiers).asList().containsExactly(
                        ConversionUtils.identifierToHalProgramIdentifier(TEST_DAB_SID_EXT_ID));
    }

    @Test
    public void vendorInfoToHalVendorKeyValues_withNull() {
        expect.withMessage("Null vendor info converted to HAL")
                .that(ConversionUtils.vendorInfoToHalVendorKeyValues(/* info= */ null)).asList()
                .isEmpty();
    }

    @Test
    public void vendorInfoToHalVendorKeyValues_withNullValue() {
        Map<String, String> vendorInfo = new ArrayMap<>();
        vendorInfo.put(VENDOR_INFO_KEY_1, null);

        expect.withMessage("Vendor info with null value converted to HAL")
                .that(ConversionUtils.vendorInfoToHalVendorKeyValues(vendorInfo)).asList()
                .isEmpty();
    }

    @Test
    public void vendorInfoFromHalVendorKeyValues_withNullElements() {
        VendorKeyValue halVendorInfo = new VendorKeyValue();
        halVendorInfo.key = null;
        halVendorInfo.value = VENDOR_INFO_VALUE_1;
        VendorKeyValue[] halVendorInfoArray = new VendorKeyValue[]{halVendorInfo};

        expect.withMessage("Null vendor info converted from HAL")
                .that(ConversionUtils.vendorInfoFromHalVendorKeyValues(halVendorInfoArray))
                .isEmpty();
    }

    private static RadioManager.ModuleProperties createModuleProperties() {
        AmFmRegionConfig amFmConfig = createAmFmRegionConfig();
        DabTableEntry[] dabTableEntries = new DabTableEntry[]{
                createDabTableEntry(DAB_ENTRY_LABEL_1, DAB_ENTRY_FREQUENCY_1),
                createDabTableEntry(DAB_ENTRY_LABEL_2, DAB_ENTRY_FREQUENCY_2)};
        return createModuleProperties(amFmConfig, dabTableEntries);
    }

    private static RadioManager.ModuleProperties createModuleProperties(
            AmFmRegionConfig amFmConfig, DabTableEntry[] dabTableEntries) {
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
                IdentifierType.RDS_PI, IdentifierType.DAB_SID_EXT, IdentifierType.HD_STATION_ID_EXT,
                IdentifierType.DRMO_SERVICE_ID};
        halProperties.maker = TEST_MAKER;
        halProperties.product = TEST_PRODUCT;
        halProperties.version = TEST_VERSION;
        halProperties.serial = TEST_SERIAL;
        halProperties.vendorInfo = new VendorKeyValue[]{
                AidlTestUtils.makeVendorKeyValue(VENDOR_INFO_KEY_1, VENDOR_INFO_VALUE_1),
                AidlTestUtils.makeVendorKeyValue(VENDOR_INFO_KEY_2, VENDOR_INFO_VALUE_2)};
        return halProperties;
    }

    private ProgramSelector.Identifier createHdStationLocationIdWithFlagEnabled() {
        return new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_HD_STATION_LOCATION,
                TEST_HD_LOCATION_VALUE);
    }

    private ProgramSelector createHdSelectorWithFlagEnabled() {
        return new ProgramSelector(ProgramSelector.PROGRAM_TYPE_FM_HD, TEST_HD_STATION_EXT_ID,
                new ProgramSelector.Identifier[]{createHdStationLocationIdWithFlagEnabled()},
                /* vendorIds= */ null);
    }
}
