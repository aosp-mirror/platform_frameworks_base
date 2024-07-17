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

package com.android.server.broadcastradio.hal2;

import static org.junit.Assert.assertThrows;

import android.hardware.broadcastradio.V2_0.AmFmBandRange;
import android.hardware.broadcastradio.V2_0.AmFmRegionConfig;
import android.hardware.broadcastradio.V2_0.DabTableEntry;
import android.hardware.broadcastradio.V2_0.IdentifierType;
import android.hardware.broadcastradio.V2_0.Metadata;
import android.hardware.broadcastradio.V2_0.MetadataKey;
import android.hardware.broadcastradio.V2_0.ProgramInfo;
import android.hardware.broadcastradio.V2_0.Properties;
import android.hardware.broadcastradio.V2_0.Result;
import android.hardware.broadcastradio.V2_0.VendorKeyValue;
import android.hardware.radio.Announcement;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;
import android.os.ParcelableException;
import android.util.ArrayMap;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class ConvertTest {

    private static final int FM_LOWER_LIMIT = 87500;
    private static final int FM_UPPER_LIMIT = 108000;
    private static final int FM_SPACING = 200;
    private static final int AM_LOWER_LIMIT = 540;
    private static final int AM_UPPER_LIMIT = 1700;
    private static final int AM_SPACING = 10;
    private static final String DAB_ENTRY_LABEL_1 = "5A";
    private static final int DAB_ENTRY_FREQUENCY_1 = 174928;
    private static final String DAB_ENTRY_LABEL_2 = "12D";
    private static final int DAB_ENTRY_FREQUENCY_2 = 229072;
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

    private static final int TEST_ENABLED_TYPE = Announcement.TYPE_EMERGENCY;
    private static final int TEST_ANNOUNCEMENT_FREQUENCY = FM_LOWER_LIMIT + FM_SPACING;

    private static final RadioManager.ModuleProperties MODULE_PROPERTIES =
            convertToModuleProperties();
    private static final Announcement ANNOUNCEMENT =
            Convert.announcementFromHal(
                    TestUtils.makeAnnouncement(TEST_ENABLED_TYPE, TEST_ANNOUNCEMENT_FREQUENCY));

    @Rule
    public final Expect expect = Expect.create();

    @Test
    public void throwOnError() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                Convert.throwOnError("tune", Result.INVALID_ARGUMENTS));

        expect.withMessage("Exception for illegeal argument").that(thrown)
                .hasMessageThat().contains("INVALID_ARGUMENTS");
    }

    @Test
    public void throwOnError_withUnknownErrorCode() {
        ParcelableException thrown = assertThrows(ParcelableException.class, () ->
                Convert.throwOnError("tune", /* result= */ 1000));

        expect.withMessage("Exception for unknown error code").that(thrown)
                .hasMessageThat().contains("unknown error");
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
    public void propertiesFromHalProperties_withInvalidBand() {
        AmFmRegionConfig amFmRegionConfig = new AmFmRegionConfig();
        amFmRegionConfig.ranges = new ArrayList<>(Arrays.asList(createAmFmBandRange(
                /* lowerBound= */ 50000, /* upperBound= */ 60000, /* spacing= */ 10),
                createAmFmBandRange(FM_LOWER_LIMIT, FM_UPPER_LIMIT, FM_SPACING)));

        RadioManager.ModuleProperties properties = convertToModuleProperties(amFmRegionConfig,
                new ArrayList<>());

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
    public void getBands_withInvalidFrequency() {
        expect.withMessage("Band for invalid frequency")
                .that(Utils.getBand(/* freq= */ 110000)).isEqualTo(FrequencyBand.UNKNOWN);
    }

    @Test
    public void vendorInfoToHal_withNull() {
        expect.withMessage("Null vendor info converted to HAL")
                .that(Convert.vendorInfoToHal(/* info= */ null)).isEmpty();
    }

    @Test
    public void vendorInfoToHal_withNullValue() {
        Map<String, String> vendorInfo = new ArrayMap<>();
        vendorInfo.put(VENDOR_INFO_KEY_1, null);

        expect.withMessage("Vendor info with null value converted to HAL")
                .that(Convert.vendorInfoToHal(vendorInfo)).isEmpty();
    }

    @Test
    public void vendorInfoFromHalVendorKeyValues_withNull() {
        expect.withMessage("Null vendor info converted from HAL")
                .that(Convert.vendorInfoFromHal(/* info= */ null)).isEmpty();
    }

    @Test
    public void vendorInfoFromHalVendorKeyValues_withNullElements() {
        VendorKeyValue halVendorInfo = new VendorKeyValue();
        halVendorInfo.key = null;
        halVendorInfo.value = "VendorValue";
        List<VendorKeyValue> halVendorInfoArray = List.of(halVendorInfo);

        expect.withMessage("Null vendor info converted from HAL")
                .that(Convert.vendorInfoFromHal(halVendorInfoArray)).isEmpty();
    }

    @Test
    public void programInfoFromHal_withMetadata() {
        int freq = 97900;
        int signalQuality = 90;
        String songTitle = "titleTest";
        int albumArt = 1;
        android.hardware.broadcastradio.V2_0.ProgramSelector halSelector =
                TestUtils.makeHalFmSelector(freq);
        ArrayList<Metadata> metadata = new ArrayList<>(List.of(
                createIntMetadata(MetadataKey.ALBUM_ART, albumArt),
                createStringMetadata(MetadataKey.SONG_TITLE, songTitle),
                createStringMetadata(/* key= */ 1000, "valueForInvalidMetadataType")));
        ProgramInfo halInfo = TestUtils.makeHalProgramInfo(halSelector, signalQuality,
                /* relatedContent= */ new ArrayList<>(), metadata);

        RadioManager.ProgramInfo info = Convert.programInfoFromHal(halInfo);

        RadioMetadata convertedMetadata = info.getMetadata();
        expect.withMessage("Metadata converted from HAL")
                .that(convertedMetadata.size()).isEqualTo(2);
        expect.withMessage("Song title")
                .that(convertedMetadata.getString(RadioMetadata.METADATA_KEY_TITLE))
                .isEqualTo(songTitle);
        expect.withMessage("Album art")
                .that(convertedMetadata.getInt(RadioMetadata.METADATA_KEY_ART))
                .isEqualTo(albumArt);
    }

    private static RadioManager.ModuleProperties convertToModuleProperties() {
        AmFmRegionConfig amFmConfig = createAmFmRegionConfig();
        List<DabTableEntry> dabTableEntries = Arrays.asList(
                createDabTableEntry(DAB_ENTRY_LABEL_1, DAB_ENTRY_FREQUENCY_1),
                createDabTableEntry(DAB_ENTRY_LABEL_2, DAB_ENTRY_FREQUENCY_2));

        return convertToModuleProperties(amFmConfig, dabTableEntries);
    }

    private static RadioManager.ModuleProperties convertToModuleProperties(
            AmFmRegionConfig amFmConfig, List<DabTableEntry> dabTableEntries) {
        Properties properties = createHalProperties();
        return Convert.propertiesFromHal(TEST_ID, TEST_SERVICE_NAME, properties,
                amFmConfig, dabTableEntries);
    }

    private static AmFmRegionConfig createAmFmRegionConfig() {
        AmFmRegionConfig amFmRegionConfig = new AmFmRegionConfig();
        amFmRegionConfig.ranges = new ArrayList<>(Arrays.asList(
                createAmFmBandRange(FM_LOWER_LIMIT, FM_UPPER_LIMIT, FM_SPACING),
                createAmFmBandRange(AM_LOWER_LIMIT, AM_UPPER_LIMIT, AM_SPACING)));
        return amFmRegionConfig;
    }

    private static AmFmBandRange createAmFmBandRange(int lowerBound, int upperBound, int spacing) {
        AmFmBandRange bandRange = new AmFmBandRange();
        bandRange.lowerBound = lowerBound;
        bandRange.upperBound = upperBound;
        bandRange.spacing = spacing;
        bandRange.scanSpacing = bandRange.spacing;
        return bandRange;
    }

    private static DabTableEntry createDabTableEntry(String label, int value) {
        DabTableEntry dabTableEntry = new DabTableEntry();
        dabTableEntry.label = label;
        dabTableEntry.frequency = value;
        return dabTableEntry;
    }

    private static Properties createHalProperties() {
        Properties halProperties = new Properties();
        halProperties.supportedIdentifierTypes = new ArrayList<Integer>(Arrays.asList(
                IdentifierType.AMFM_FREQUENCY, IdentifierType.RDS_PI, IdentifierType.DAB_SID_EXT,
                IdentifierType.HD_STATION_ID_EXT, IdentifierType.DRMO_SERVICE_ID));
        halProperties.maker = TEST_MAKER;
        halProperties.product = TEST_PRODUCT;
        halProperties.version = TEST_VERSION;
        halProperties.serial = TEST_SERIAL;
        halProperties.vendorInfo = new ArrayList<>(Arrays.asList(
                TestUtils.makeVendorKeyValue(VENDOR_INFO_KEY_1, VENDOR_INFO_VALUE_1),
                TestUtils.makeVendorKeyValue(VENDOR_INFO_KEY_2, VENDOR_INFO_VALUE_2)));
        return halProperties;
    }

    private Metadata createStringMetadata(int key, String value) {
        Metadata metadata = new Metadata();
        metadata.key = key;
        metadata.stringValue = value;
        return metadata;
    }

    private Metadata createIntMetadata(int key, int value) {
        Metadata metadata = new Metadata();
        metadata.key = key;
        metadata.intValue = value;
        return metadata;
    }
}
