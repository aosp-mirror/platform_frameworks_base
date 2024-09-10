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

package android.hardware.radio;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Parcel;
import android.os.RemoteException;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.ArrayMap;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(MockitoJUnitRunner.class)
public final class RadioManagerTest {

    private static final int REGION = RadioManager.REGION_ITU_2;
    private static final int FM_LOWER_LIMIT = 87500;
    private static final int FM_UPPER_LIMIT = 108000;
    private static final int FM_SPACING = 200;
    private static final int AM_LOWER_LIMIT = 540;
    private static final int AM_UPPER_LIMIT = 1700;
    private static final int AM_SPACING = 10;
    private static final boolean STEREO_SUPPORTED = true;
    private static final boolean RDS_SUPPORTED = true;
    private static final boolean TA_SUPPORTED = false;
    private static final boolean AF_SUPPORTED = false;
    private static final boolean EA_SUPPORTED = false;

    private static final int PROPERTIES_ID = 10;
    private static final String SERVICE_NAME = "ServiceNameMock";
    private static final int CLASS_ID = RadioManager.CLASS_AM_FM;
    private static final String IMPLEMENTOR = "ImplementorMock";
    private static final String PRODUCT = "ProductMock";
    private static final String VERSION = "VersionMock";
    private static final String SERIAL = "SerialMock";
    private static final int NUM_TUNERS = 1;
    private static final int NUM_AUDIO_SOURCES = 1;
    private static final boolean IS_INITIALIZATION_REQUIRED = false;
    private static final boolean IS_CAPTURE_SUPPORTED = false;
    private static final boolean IS_BG_SCAN_SUPPORTED = true;
    private static final int[] SUPPORTED_PROGRAM_TYPES = new int[]{
            ProgramSelector.PROGRAM_TYPE_AM, ProgramSelector.PROGRAM_TYPE_FM};
    private static final int[] SUPPORTED_IDENTIFIERS_TYPES = new int[]{
            ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, ProgramSelector.IDENTIFIER_TYPE_RDS_PI};

    private static final int CREATOR_ARRAY_SIZE = 3;

    private static final RadioManager.FmBandDescriptor FM_BAND_DESCRIPTOR =
            createFmBandDescriptor();
    private static final RadioManager.AmBandDescriptor AM_BAND_DESCRIPTOR =
            createAmBandDescriptor();
    private static final RadioManager.FmBandConfig FM_BAND_CONFIG = createFmBandConfig();
    private static final RadioManager.AmBandConfig AM_BAND_CONFIG = createAmBandConfig();
    private static final RadioManager.ModuleProperties AMFM_PROPERTIES =
            createAmFmProperties(/* dabFrequencyTable= */ null);

    private static final int DAB_INFO_FLAG_LIVE_VALUE = 1;
    private static final int DAB_INFO_FLAG_TUNED_VALUE = 1 << 4;
    private static final int DAB_INFO_FLAG_STEREO_VALUE = 1 << 5;
    private static final int HD_INFO_FLAG_LIVE_VALUE = 1;
    private static final int HD_INFO_FLAG_TUNED_VALUE = 1 << 4;
    private static final int HD_INFO_FLAG_STEREO_VALUE = 1 << 5;
    private static final int HD_INFO_FLAG_SIGNAL_ACQUISITION_VALUE = 1 << 6;
    private static final int HD_INFO_FLAG_SIS_ACQUISITION_VALUE = 1 << 7;
    /**
     * Info flags with live, tuned, and stereo enabled for DAB program
     */
    private static final int INFO_FLAGS_DAB = DAB_INFO_FLAG_LIVE_VALUE | DAB_INFO_FLAG_TUNED_VALUE
            | DAB_INFO_FLAG_STEREO_VALUE;
    /**
     * HD program info flags with live, tuned, stereo enabled, signal acquired, SIS information
     * available but audio unavailable
     */
    private static final int INFO_FLAGS_HD = HD_INFO_FLAG_LIVE_VALUE | HD_INFO_FLAG_TUNED_VALUE
            | HD_INFO_FLAG_STEREO_VALUE | HD_INFO_FLAG_SIGNAL_ACQUISITION_VALUE
            | HD_INFO_FLAG_SIS_ACQUISITION_VALUE;
    private static final int SIGNAL_QUALITY = 2;
    private static final ProgramSelector.Identifier DAB_SID_EXT_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT,
                    /* value= */ 0xA000000111L);
    private static final ProgramSelector.Identifier DAB_SID_EXT_IDENTIFIER_RELATED =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT,
                    /* value= */ 0xA000000113L);
    private static final ProgramSelector.Identifier DAB_ENSEMBLE_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE,
                    /* value= */ 0x1013);
    private static final ProgramSelector.Identifier DAB_FREQUENCY_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY,
                    /* value= */ 220352);
    private static final ProgramSelector DAB_SELECTOR =
            new ProgramSelector(ProgramSelector.PROGRAM_TYPE_DAB, DAB_SID_EXT_IDENTIFIER,
                    new ProgramSelector.Identifier[]{
                            DAB_ENSEMBLE_IDENTIFIER, DAB_FREQUENCY_IDENTIFIER},
                    /* vendorIds= */ null);

    private static final long HD_FREQUENCY = 97_100;
    private static final ProgramSelector.Identifier HD_STATION_EXT_IDENTIFIER =
            new ProgramSelector.Identifier(ProgramSelector.IDENTIFIER_TYPE_HD_STATION_ID_EXT,
                    /* value= */ (HD_FREQUENCY << 36) | 0x1L);
    private static final ProgramSelector HD_SELECTOR = new ProgramSelector(
            ProgramSelector.PROGRAM_TYPE_FM_HD, HD_STATION_EXT_IDENTIFIER,
            new ProgramSelector.Identifier[]{}, /* vendorIds= */ null);

    private static final RadioMetadata METADATA = createMetadata();
    private static final RadioManager.ProgramInfo DAB_PROGRAM_INFO =
            createDabProgramInfo(DAB_SELECTOR);
    private static final RadioManager.ProgramInfo HD_PROGRAM_INFO = createHdProgramInfo(
            HD_SELECTOR);

    private static final int EVENT_ANNOUNCEMENT_TYPE = Announcement.TYPE_EVENT;
    private static final List<Announcement> TEST_ANNOUNCEMENT_LIST = Arrays.asList(
            new Announcement(DAB_SELECTOR, EVENT_ANNOUNCEMENT_TYPE,
                    /* vendorInfo= */ new ArrayMap<>()));

    private RadioManager mRadioManager;
    private final ApplicationInfo mApplicationInfo = new ApplicationInfo();

    @Rule
    public final Expect mExpect = Expect.create();

    @Mock
    private IRadioService mRadioServiceMock;
    @Mock
    private Context mContextMock;
    @Mock
    private RadioTuner.Callback mCallbackMock;
    @Mock
    private Announcement.OnListUpdatedListener mEventListener;
    @Mock
    private ICloseHandle mCloseHandleMock;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void constructor_withUnsupportedTypeForBandDescriptor_throwsException() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new RadioManager.AmBandDescriptor(REGION, /* type= */ 100, AM_LOWER_LIMIT,
                        AM_UPPER_LIMIT, AM_SPACING, STEREO_SUPPORTED));

        mExpect.withMessage("Unsupported band type exception")
                .that(thrown).hasMessageThat().contains("Unsupported band");
    }

    @Test
    public void getType_forBandDescriptor() {
        RadioManager.BandDescriptor bandDescriptor = createAmBandDescriptor();

        mExpect.withMessage("AM Band Descriptor type")
                .that(bandDescriptor.getType()).isEqualTo(RadioManager.BAND_AM);
    }

    @Test
    public void getRegion_forBandDescriptor() {
        RadioManager.BandDescriptor bandDescriptor = createFmBandDescriptor();

        mExpect.withMessage("FM Band Descriptor region")
                .that(bandDescriptor.getRegion()).isEqualTo(REGION);
    }

    @Test
    public void getLowerLimit_forBandDescriptor() {
        RadioManager.BandDescriptor bandDescriptor = createFmBandDescriptor();

        mExpect.withMessage("FM Band Descriptor lower limit")
                .that(bandDescriptor.getLowerLimit()).isEqualTo(FM_LOWER_LIMIT);
    }

    @Test
    public void getUpperLimit_forBandDescriptor() {
        RadioManager.BandDescriptor bandDescriptor = createAmBandDescriptor();

        mExpect.withMessage("AM Band Descriptor upper limit")
                .that(bandDescriptor.getUpperLimit()).isEqualTo(AM_UPPER_LIMIT);
    }

    @Test
    public void getSpacing_forBandDescriptor() {
        RadioManager.BandDescriptor bandDescriptor = createAmBandDescriptor();

        mExpect.withMessage("AM Band Descriptor spacing")
                .that(bandDescriptor.getSpacing()).isEqualTo(AM_SPACING);
    }

    @Test
    public void describeContents_forBandDescriptor() {
        RadioManager.BandDescriptor bandDescriptor = createFmBandDescriptor();

        mExpect.withMessage("Band Descriptor contents")
                .that(bandDescriptor.describeContents()).isEqualTo(0);
    }

    @Test
    public void writeToParcel_forBandDescriptor() {
        Parcel parcel = Parcel.obtain();
        RadioManager.BandDescriptor bandDescriptor = createFmBandDescriptor();

        bandDescriptor.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        RadioManager.BandDescriptor bandDescriptorFromParcel =
                RadioManager.BandDescriptor.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("Band Descriptor created from parcel")
                .that(bandDescriptorFromParcel).isEqualTo(bandDescriptor);
    }

    @Test
    public void newArray_forBandDescriptorCreator() {
        RadioManager.BandDescriptor[] bandDescriptors =
                RadioManager.BandDescriptor.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("Band Descriptors").that(bandDescriptors).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void isAmBand_forAmBandDescriptor_returnsTrue() {
        RadioManager.BandDescriptor bandDescriptor = createAmBandDescriptor();

        mExpect.withMessage("Is AM Band Descriptor an AM band")
                .that(bandDescriptor.isAmBand()).isTrue();
    }

    @Test
    public void isFmBand_forAmBandDescriptor_returnsFalse() {
        RadioManager.BandDescriptor bandDescriptor = createAmBandDescriptor();

        mExpect.withMessage("Is AM Band Descriptor an FM band")
                .that(bandDescriptor.isFmBand()).isFalse();
    }

    @Test
    public void isStereoSupported_forFmBandDescriptor() {
        mExpect.withMessage("FM Band Descriptor stereo")
                .that(FM_BAND_DESCRIPTOR.isStereoSupported()).isEqualTo(STEREO_SUPPORTED);
    }

    @Test
    public void isRdsSupported_forFmBandDescriptor() {
        mExpect.withMessage("FM Band Descriptor RDS or RBDS")
                .that(FM_BAND_DESCRIPTOR.isRdsSupported()).isEqualTo(RDS_SUPPORTED);
    }

    @Test
    public void isTaSupported_forFmBandDescriptor() {
        mExpect.withMessage("FM Band Descriptor traffic announcement")
                .that(FM_BAND_DESCRIPTOR.isTaSupported()).isEqualTo(TA_SUPPORTED);
    }

    @Test
    public void isAfSupported_forFmBandDescriptor() {
        mExpect.withMessage("FM Band Descriptor alternate frequency")
                .that(FM_BAND_DESCRIPTOR.isAfSupported()).isEqualTo(AF_SUPPORTED);
    }

    @Test
    public void isEaSupported_forFmBandDescriptor() {
        mExpect.withMessage("FM Band Descriptor emergency announcement")
                .that(FM_BAND_DESCRIPTOR.isEaSupported()).isEqualTo(EA_SUPPORTED);
    }

    @Test
    public void describeContents_forFmBandDescriptor() {
        mExpect.withMessage("FM Band Descriptor contents")
                .that(FM_BAND_DESCRIPTOR.describeContents()).isEqualTo(0);
    }

    @Test
    public void writeToParcel_forFmBandDescriptor() {
        Parcel parcel = Parcel.obtain();

        FM_BAND_DESCRIPTOR.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        RadioManager.FmBandDescriptor fmBandDescriptorFromParcel =
                RadioManager.FmBandDescriptor.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("FM Band Descriptor created from parcel")
                .that(fmBandDescriptorFromParcel).isEqualTo(FM_BAND_DESCRIPTOR);
    }

    @Test
    public void newArray_forFmBandDescriptorCreator() {
        RadioManager.FmBandDescriptor[] fmBandDescriptors =
                RadioManager.FmBandDescriptor.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("FM Band Descriptors")
                .that(fmBandDescriptors).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void isStereoSupported_forAmBandDescriptor() {
        mExpect.withMessage("AM Band Descriptor stereo")
                .that(AM_BAND_DESCRIPTOR.isStereoSupported()).isEqualTo(STEREO_SUPPORTED);
    }

    @Test
    public void describeContents_forAmBandDescriptor() {
        mExpect.withMessage("AM Band Descriptor contents")
                .that(AM_BAND_DESCRIPTOR.describeContents()).isEqualTo(0);
    }

    @Test
    public void writeToParcel_forAmBandDescriptor() {
        Parcel parcel = Parcel.obtain();

        AM_BAND_DESCRIPTOR.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        RadioManager.AmBandDescriptor amBandDescriptorFromParcel =
                RadioManager.AmBandDescriptor.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("FM Band Descriptor created from parcel")
                .that(amBandDescriptorFromParcel).isEqualTo(AM_BAND_DESCRIPTOR);
    }

    @Test
    public void newArray_forAmBandDescriptorCreator() {
        RadioManager.AmBandDescriptor[] amBandDescriptors =
                RadioManager.AmBandDescriptor.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("AM Band Descriptors")
                .that(amBandDescriptors).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void equals_withSameFmBandDescriptors_returnsTrue() {
        RadioManager.FmBandDescriptor fmBandDescriptorCompared = createFmBandDescriptor();

        mExpect.withMessage("The same FM Band Descriptor")
                .that(FM_BAND_DESCRIPTOR).isEqualTo(fmBandDescriptorCompared);
    }

    @Test
    public void equals_withSameAmBandDescriptors_returnsTrue() {
        RadioManager.AmBandDescriptor amBandDescriptorCompared = createAmBandDescriptor();

        mExpect.withMessage("The same AM Band Descriptor")
                .that(AM_BAND_DESCRIPTOR).isEqualTo(amBandDescriptorCompared);
    }

    @Test
    public void equals_withAmBandDescriptorsAndOtherTypeObject() {
        mExpect.withMessage("AM Band Descriptor")
                .that(AM_BAND_DESCRIPTOR).isNotEqualTo(FM_BAND_DESCRIPTOR);
    }

    @Test
    public void equals_withFmBandDescriptorsAndOtherTypeObject() {
        mExpect.withMessage("FM Band Descriptor")
                .that(FM_BAND_DESCRIPTOR).isNotEqualTo(AM_BAND_DESCRIPTOR);
    }

    @Test
    public void equals_withAmBandDescriptorsOfDifferentUpperLimits_returnsFalse() {
        RadioManager.AmBandDescriptor amBandDescriptorCompared =
                new RadioManager.AmBandDescriptor(REGION, RadioManager.BAND_AM, AM_LOWER_LIMIT,
                        AM_UPPER_LIMIT + AM_SPACING, AM_SPACING, STEREO_SUPPORTED);

        mExpect.withMessage("AM Band Descriptor of different upper limit")
                .that(AM_BAND_DESCRIPTOR).isNotEqualTo(amBandDescriptorCompared);
    }

    @Test
    public void equals_withAmBandDescriptorsOfDifferentStereoSupportValues() {
        RadioManager.AmBandDescriptor amBandDescriptorCompared =
                new RadioManager.AmBandDescriptor(REGION, RadioManager.BAND_AM, AM_LOWER_LIMIT,
                        AM_UPPER_LIMIT, AM_SPACING, !STEREO_SUPPORTED);

        mExpect.withMessage("AM Band Descriptor of different stereo support values")
                .that(AM_BAND_DESCRIPTOR).isNotEqualTo(amBandDescriptorCompared);
    }

    @Test
    public void equals_withFmBandDescriptorsOfDifferentSpacingValues() {
        RadioManager.FmBandDescriptor fmBandDescriptorCompared = new RadioManager.FmBandDescriptor(
                REGION, RadioManager.BAND_FM, FM_LOWER_LIMIT, FM_UPPER_LIMIT, FM_SPACING * 2,
                STEREO_SUPPORTED, RDS_SUPPORTED, TA_SUPPORTED, AF_SUPPORTED, EA_SUPPORTED);

        mExpect.withMessage("FM Band Descriptors of different support limit values")
                .that(FM_BAND_DESCRIPTOR).isNotEqualTo(fmBandDescriptorCompared);
    }

    @Test
    public void equals_withFmBandConfigsOfDifferentLowerLimitValues() {
        RadioManager.FmBandDescriptor fmBandDescriptorCompared = new RadioManager.FmBandDescriptor(
                REGION + 1, RadioManager.BAND_AM_HD, AM_LOWER_LIMIT, AM_UPPER_LIMIT, AM_SPACING,
                STEREO_SUPPORTED, RDS_SUPPORTED, TA_SUPPORTED, AF_SUPPORTED, EA_SUPPORTED);

        mExpect.withMessage("FM Band Descriptors of different region values")
                .that(FM_BAND_DESCRIPTOR).isNotEqualTo(fmBandDescriptorCompared);
    }

    @Test
    public void equals_withFmBandDescriptorsOfDifferentStereoSupportValues() {
        RadioManager.FmBandDescriptor fmBandDescriptorCompared = new RadioManager.FmBandDescriptor(
                REGION, RadioManager.BAND_FM, FM_LOWER_LIMIT, FM_UPPER_LIMIT, FM_SPACING,
                !STEREO_SUPPORTED, RDS_SUPPORTED, TA_SUPPORTED, AF_SUPPORTED, EA_SUPPORTED);

        mExpect.withMessage("FM Band Descriptors of different stereo support values")
                .that(fmBandDescriptorCompared).isNotEqualTo(FM_BAND_DESCRIPTOR);
    }

    @Test
    public void equals_withFmBandDescriptorsOfDifferentRdsSupportValues() {
        RadioManager.FmBandDescriptor fmBandDescriptorCompared = new RadioManager.FmBandDescriptor(
                REGION, RadioManager.BAND_FM, FM_LOWER_LIMIT, FM_UPPER_LIMIT, FM_SPACING,
                STEREO_SUPPORTED, !RDS_SUPPORTED, TA_SUPPORTED, AF_SUPPORTED, EA_SUPPORTED);

        mExpect.withMessage("FM Band Descriptors of different rds support values")
                .that(fmBandDescriptorCompared).isNotEqualTo(FM_BAND_DESCRIPTOR);
    }

    @Test
    public void equals_withFmBandDescriptorsOfDifferentTaSupportValues() {
        RadioManager.FmBandDescriptor fmBandDescriptorCompared = new RadioManager.FmBandDescriptor(
                REGION, RadioManager.BAND_FM, FM_LOWER_LIMIT, FM_UPPER_LIMIT, FM_SPACING,
                STEREO_SUPPORTED, RDS_SUPPORTED, !TA_SUPPORTED, AF_SUPPORTED, EA_SUPPORTED);

        mExpect.withMessage("FM Band Descriptors of different ta support values")
                .that(fmBandDescriptorCompared).isNotEqualTo(FM_BAND_DESCRIPTOR);
    }

    @Test
    public void equals_withFmBandDescriptorsOfDifferentAfSupportValues() {
        RadioManager.FmBandDescriptor fmBandDescriptorCompared = new RadioManager.FmBandDescriptor(
                REGION, RadioManager.BAND_FM, FM_LOWER_LIMIT, FM_UPPER_LIMIT, FM_SPACING,
                STEREO_SUPPORTED, RDS_SUPPORTED, TA_SUPPORTED, !AF_SUPPORTED, EA_SUPPORTED);

        mExpect.withMessage("FM Band Descriptors of different af support values")
                .that(fmBandDescriptorCompared).isNotEqualTo(FM_BAND_DESCRIPTOR);
    }

    @Test
    public void equals_withFmBandDescriptorsOfDifferentEaSupportValues() {
        RadioManager.FmBandDescriptor fmBandDescriptorCompared = new RadioManager.FmBandDescriptor(
                REGION, RadioManager.BAND_FM, FM_LOWER_LIMIT, FM_UPPER_LIMIT, FM_SPACING,
                STEREO_SUPPORTED, RDS_SUPPORTED, TA_SUPPORTED, AF_SUPPORTED, !EA_SUPPORTED);

        mExpect.withMessage("FM Band Descriptors of different ea support values")
                .that(fmBandDescriptorCompared).isNotEqualTo(FM_BAND_DESCRIPTOR);
    }

    @Test
    public void hashCode_withSameFmBandDescriptors_equals() {
        RadioManager.FmBandDescriptor fmBandDescriptorCompared = createFmBandDescriptor();

        mExpect.withMessage("Hash code of the same FM Band Descriptor")
                .that(fmBandDescriptorCompared.hashCode()).isEqualTo(FM_BAND_DESCRIPTOR.hashCode());
    }

    @Test
    public void hashCode_withSameAmBandDescriptors_equals() {
        RadioManager.AmBandDescriptor amBandDescriptorCompared = createAmBandDescriptor();

        mExpect.withMessage("Hash code of the same AM Band Descriptor")
                .that(amBandDescriptorCompared.hashCode()).isEqualTo(AM_BAND_DESCRIPTOR.hashCode());
    }

    @Test
    public void hashCode_withFmBandDescriptorsOfDifferentAfSupports_notEquals() {
        RadioManager.FmBandDescriptor fmBandDescriptorCompared = new RadioManager.FmBandDescriptor(
                REGION, RadioManager.BAND_FM, FM_LOWER_LIMIT, FM_UPPER_LIMIT, FM_SPACING,
                STEREO_SUPPORTED, RDS_SUPPORTED, TA_SUPPORTED, !AF_SUPPORTED, EA_SUPPORTED);

        mExpect.withMessage("Hash code of FM Band Descriptor of different spacing")
                .that(fmBandDescriptorCompared.hashCode())
                .isNotEqualTo(FM_BAND_DESCRIPTOR.hashCode());
    }

    @Test
    public void hashCode_withAmBandDescriptorsOfDifferentSpacings_notEquals() {
        RadioManager.AmBandDescriptor amBandDescriptorCompared =
                new RadioManager.AmBandDescriptor(REGION, RadioManager.BAND_AM, AM_LOWER_LIMIT,
                        AM_UPPER_LIMIT, AM_SPACING * 2, STEREO_SUPPORTED);

        mExpect.withMessage("Hash code of AM Band Descriptor of different spacing")
                .that(amBandDescriptorCompared.hashCode())
                .isNotEqualTo(AM_BAND_DESCRIPTOR.hashCode());
    }

    @Test
    public void getType_forBandConfig() {
        RadioManager.BandConfig fmBandConfig = createFmBandConfig();

        mExpect.withMessage("FM Band Config type")
                .that(fmBandConfig.getType()).isEqualTo(RadioManager.BAND_FM);
    }

    @Test
    public void getRegion_forBandConfig() {
        RadioManager.BandConfig amBandConfig = createAmBandConfig();

        mExpect.withMessage("AM Band Config region")
                .that(amBandConfig.getRegion()).isEqualTo(REGION);
    }

    @Test
    public void getLowerLimit_forBandConfig() {
        RadioManager.BandConfig amBandConfig = createAmBandConfig();

        mExpect.withMessage("AM Band Config lower limit")
                .that(amBandConfig.getLowerLimit()).isEqualTo(AM_LOWER_LIMIT);
    }

    @Test
    public void getUpperLimit_forBandConfig() {
        RadioManager.BandConfig fmBandConfig = createFmBandConfig();

        mExpect.withMessage("FM Band Config upper limit")
                .that(fmBandConfig.getUpperLimit()).isEqualTo(FM_UPPER_LIMIT);
    }

    @Test
    public void getSpacing_forBandConfig() {
        RadioManager.BandConfig fmBandConfig = createFmBandConfig();

        mExpect.withMessage("FM Band Config spacing")
                .that(fmBandConfig.getSpacing()).isEqualTo(FM_SPACING);
    }

    @Test
    public void describeContents_forBandConfig() {
        RadioManager.BandConfig bandConfig = createFmBandConfig();

        mExpect.withMessage("FM Band Config contents")
                .that(bandConfig.describeContents()).isEqualTo(0);
    }

    @Test
    public void writeToParcel_forBandConfig() {
        Parcel parcel = Parcel.obtain();
        RadioManager.BandConfig bandConfig = createAmBandConfig();

        bandConfig.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        RadioManager.BandConfig bandConfigFromParcel =
                RadioManager.BandConfig.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("Band Config created from parcel")
                .that(bandConfigFromParcel).isEqualTo(bandConfig);
    }

    @Test
    public void newArray_forBandConfigCreator() {
        RadioManager.BandConfig[] bandConfigs =
                RadioManager.BandConfig.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("Band Configs").that(bandConfigs).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void getStereo_forFmBandConfig() {
        mExpect.withMessage("FM Band Config stereo")
                .that(FM_BAND_CONFIG.getStereo()).isEqualTo(STEREO_SUPPORTED);
    }

    @Test
    public void getRds_forFmBandConfig() {
        mExpect.withMessage("FM Band Config RDS or RBDS")
                .that(FM_BAND_CONFIG.getRds()).isEqualTo(RDS_SUPPORTED);
    }

    @Test
    public void getTa_forFmBandConfig() {
        mExpect.withMessage("FM Band Config traffic announcement")
                .that(FM_BAND_CONFIG.getTa()).isEqualTo(TA_SUPPORTED);
    }

    @Test
    public void getAf_forFmBandConfig() {
        mExpect.withMessage("FM Band Config alternate frequency")
                .that(FM_BAND_CONFIG.getAf()).isEqualTo(AF_SUPPORTED);
    }

    @Test
    public void getEa_forFmBandConfig() {
        mExpect.withMessage("FM Band Config emergency Announcement")
                .that(FM_BAND_CONFIG.getEa()).isEqualTo(EA_SUPPORTED);
    }

    @Test
    public void describeContents_forFmBandConfig() {
        mExpect.withMessage("FM Band Config contents")
                .that(FM_BAND_CONFIG.describeContents()).isEqualTo(0);
    }

    @Test
    public void writeToParcel_forFmBandConfig() {
        Parcel parcel = Parcel.obtain();

        FM_BAND_CONFIG.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        RadioManager.FmBandConfig fmBandConfigFromParcel =
                RadioManager.FmBandConfig.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("FM Band Config created from parcel")
                .that(fmBandConfigFromParcel).isEqualTo(FM_BAND_CONFIG);
    }

    @Test
    public void newArray_forFmBandConfigCreator() {
        RadioManager.FmBandConfig[] fmBandConfigs =
                RadioManager.FmBandConfig.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("FM Band Configs").that(fmBandConfigs).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void getStereo_forAmBandConfig() {
        mExpect.withMessage("AM Band Config stereo")
                .that(AM_BAND_CONFIG.getStereo()).isEqualTo(STEREO_SUPPORTED);
    }

    @Test
    public void describeContents_forAmBandConfig() {
        mExpect.withMessage("AM Band Config contents")
                .that(AM_BAND_CONFIG.describeContents()).isEqualTo(0);
    }

    @Test
    public void writeToParcel_forAmBandConfig() {
        Parcel parcel = Parcel.obtain();

        AM_BAND_CONFIG.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        RadioManager.AmBandConfig amBandConfigFromParcel =
                RadioManager.AmBandConfig.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("AM Band Config created from parcel")
                .that(amBandConfigFromParcel).isEqualTo(AM_BAND_CONFIG);
    }

    @Test
    public void newArray_forAmBandConfigCreator() {
        RadioManager.AmBandConfig[] amBandConfigs =
                RadioManager.AmBandConfig.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("AM Band Configs").that(amBandConfigs).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void equals_withSameFmBandConfigs_returnsTrue() {
        RadioManager.FmBandConfig.Builder builder =
                new RadioManager.FmBandConfig.Builder(FM_BAND_CONFIG);
        RadioManager.FmBandConfig fmBandConfigCompared = builder.build();

        mExpect.withMessage("The same FM Band Config")
                .that(FM_BAND_CONFIG).isEqualTo(fmBandConfigCompared);
    }

    @Test
    public void equals_withFmBandConfigsOfDifferentRegionValues() {
        RadioManager.FmBandConfig fmBandConfigCompared = new RadioManager.FmBandConfig(
                new RadioManager.FmBandDescriptor(REGION + 1, RadioManager.BAND_AM_HD,
                        AM_LOWER_LIMIT, AM_UPPER_LIMIT, AM_SPACING, STEREO_SUPPORTED, RDS_SUPPORTED,
                        TA_SUPPORTED, AF_SUPPORTED, EA_SUPPORTED));

        mExpect.withMessage("FM Band Config of different regions")
                .that(FM_BAND_CONFIG).isNotEqualTo(fmBandConfigCompared);
    }

    @Test
    public void equals_withFmBandConfigsOfDifferentStereoSupportValues() {
        RadioManager.FmBandConfig fmBandConfigCompared = new RadioManager.FmBandConfig(
                new RadioManager.FmBandDescriptor(REGION, RadioManager.BAND_FM, FM_LOWER_LIMIT,
                        FM_UPPER_LIMIT, FM_SPACING, !STEREO_SUPPORTED, RDS_SUPPORTED, TA_SUPPORTED,
                        AF_SUPPORTED, EA_SUPPORTED));

        mExpect.withMessage("FM Band Config with different stereo support values")
                .that(fmBandConfigCompared).isNotEqualTo(FM_BAND_CONFIG);
    }

    @Test
    public void equals_withFmBandConfigsOfDifferentRdsSupportValues() {
        RadioManager.FmBandConfig fmBandConfigCompared = new RadioManager.FmBandConfig(
                new RadioManager.FmBandDescriptor(REGION, RadioManager.BAND_FM, FM_LOWER_LIMIT,
                        FM_UPPER_LIMIT, FM_SPACING, STEREO_SUPPORTED, !RDS_SUPPORTED, TA_SUPPORTED,
                        AF_SUPPORTED, EA_SUPPORTED));

        mExpect.withMessage("FM Band Config with different RDS support values")
                .that(fmBandConfigCompared).isNotEqualTo(FM_BAND_CONFIG);
    }

    @Test
    public void equals_withFmBandConfigsOfDifferentTaSupportValues() {
        RadioManager.FmBandConfig fmBandConfigCompared = new RadioManager.FmBandConfig(
                new RadioManager.FmBandDescriptor(REGION, RadioManager.BAND_FM, FM_LOWER_LIMIT,
                        FM_UPPER_LIMIT, FM_SPACING, STEREO_SUPPORTED, RDS_SUPPORTED, !TA_SUPPORTED,
                        AF_SUPPORTED, EA_SUPPORTED));

        mExpect.withMessage("FM Band Configs with different ta values")
                .that(fmBandConfigCompared).isNotEqualTo(FM_BAND_CONFIG);
    }

    @Test
    public void equals_withFmBandConfigsOfDifferentAfSupportValues() {
        RadioManager.FmBandConfig.Builder builder = new RadioManager.FmBandConfig.Builder(
                createFmBandDescriptor()).setStereo(STEREO_SUPPORTED).setRds(RDS_SUPPORTED)
                .setTa(TA_SUPPORTED).setAf(!AF_SUPPORTED).setEa(EA_SUPPORTED);
        RadioManager.FmBandConfig fmBandConfigCompared = builder.build();

        mExpect.withMessage("FM Band Config of different af support value")
                .that(FM_BAND_CONFIG).isNotEqualTo(fmBandConfigCompared);
    }

    @Test
    public void equals_withFmBandConfigsOfDifferentEaSupportValues() {
        RadioManager.FmBandConfig fmBandConfigCompared = new RadioManager.FmBandConfig(
                new RadioManager.FmBandDescriptor(REGION, RadioManager.BAND_FM, FM_LOWER_LIMIT,
                        FM_UPPER_LIMIT, FM_SPACING, STEREO_SUPPORTED, RDS_SUPPORTED, TA_SUPPORTED,
                        AF_SUPPORTED, !EA_SUPPORTED));

        mExpect.withMessage("FM Band Configs with different ea support values")
                .that(fmBandConfigCompared).isNotEqualTo(FM_BAND_CONFIG);
    }

    @Test
    public void equals_withAmBandConfigsAndOtherTypeObject() {
        mExpect.withMessage("AM Band Config")
                .that(AM_BAND_CONFIG).isNotEqualTo(FM_BAND_CONFIG);
    }

    @Test
    public void equals_withFmBandConfigsAndOtherTypeObject() {
        mExpect.withMessage("FM Band Config")
                .that(FM_BAND_CONFIG).isNotEqualTo(AM_BAND_CONFIG);
    }

    @Test
    public void equals_withSameAmBandConfigs_returnsTrue() {
        RadioManager.AmBandConfig.Builder builder =
                new RadioManager.AmBandConfig.Builder(AM_BAND_CONFIG);
        RadioManager.AmBandConfig amBandConfigCompared = builder.build();

        mExpect.withMessage("The same AM Band Config")
                .that(AM_BAND_CONFIG).isEqualTo(amBandConfigCompared);
    }

    @Test
    public void equals_withAmBandConfigsOfDifferentTypes_returnsFalse() {
        RadioManager.AmBandConfig amBandConfigCompared = new RadioManager.AmBandConfig(
                new RadioManager.AmBandDescriptor(REGION, RadioManager.BAND_AM_HD, AM_LOWER_LIMIT,
                        AM_UPPER_LIMIT, AM_SPACING, STEREO_SUPPORTED));

        mExpect.withMessage("AM Band Config of different type")
                .that(AM_BAND_CONFIG).isNotEqualTo(amBandConfigCompared);
    }

    @Test
    public void equals_withAmBandConfigsOfDifferentStereoValues_returnsFalse() {
        RadioManager.AmBandConfig.Builder builder = new RadioManager.AmBandConfig.Builder(
                createAmBandDescriptor()).setStereo(!STEREO_SUPPORTED);
        RadioManager.AmBandConfig amBandConfigFromBuilder = builder.build();

        mExpect.withMessage("AM Band Config of different stereo value")
                .that(AM_BAND_CONFIG).isNotEqualTo(amBandConfigFromBuilder);
    }

    @Test
    public void hashCode_withSameFmBandConfigs_equals() {
        RadioManager.FmBandConfig fmBandConfigCompared = createFmBandConfig();

        mExpect.withMessage("Hash code of the same FM Band Config")
                .that(FM_BAND_CONFIG.hashCode()).isEqualTo(fmBandConfigCompared.hashCode());
    }

    @Test
    public void hashCode_withSameAmBandConfigs_equals() {
        RadioManager.AmBandConfig amBandConfigCompared = createAmBandConfig();

        mExpect.withMessage("Hash code of the same AM Band Config")
                .that(amBandConfigCompared.hashCode()).isEqualTo(AM_BAND_CONFIG.hashCode());
    }

    @Test
    public void hashCode_withFmBandConfigsOfDifferentTypes_notEquals() {
        RadioManager.FmBandConfig fmBandConfigCompared = new RadioManager.FmBandConfig(
                new RadioManager.FmBandDescriptor(REGION, RadioManager.BAND_FM_HD, FM_LOWER_LIMIT,
                        FM_UPPER_LIMIT, FM_SPACING, STEREO_SUPPORTED, RDS_SUPPORTED, TA_SUPPORTED,
                        AF_SUPPORTED, EA_SUPPORTED));

        mExpect.withMessage("Hash code of FM Band Config with different type")
                .that(fmBandConfigCompared.hashCode()).isNotEqualTo(FM_BAND_CONFIG.hashCode());
    }

    @Test
    public void hashCode_withAmBandConfigsOfDifferentStereoSupports_notEquals() {
        RadioManager.AmBandConfig amBandConfigCompared = new RadioManager.AmBandConfig(
                new RadioManager.AmBandDescriptor(REGION, RadioManager.BAND_AM, AM_LOWER_LIMIT,
                        AM_UPPER_LIMIT, AM_SPACING, !STEREO_SUPPORTED));

        mExpect.withMessage("Hash code of AM Band Config with different stereo support")
                .that(amBandConfigCompared.hashCode()).isNotEqualTo(AM_BAND_CONFIG.hashCode());
    }

    @Test
    public void getId_forModuleProperties() {
        mExpect.withMessage("Properties id")
                .that(AMFM_PROPERTIES.getId()).isEqualTo(PROPERTIES_ID);
    }

    @Test
    public void getServiceName_forModuleProperties() {
        mExpect.withMessage("Properties service name")
                .that(AMFM_PROPERTIES.getServiceName()).isEqualTo(SERVICE_NAME);
    }

    @Test
    public void getClassId_forModuleProperties() {
        mExpect.withMessage("Properties class ID")
                .that(AMFM_PROPERTIES.getClassId()).isEqualTo(CLASS_ID);
    }

    @Test
    public void getImplementor_forModuleProperties() {
        mExpect.withMessage("Properties implementor")
                .that(AMFM_PROPERTIES.getImplementor()).isEqualTo(IMPLEMENTOR);
    }

    @Test
    public void getProduct_forModuleProperties() {
        mExpect.withMessage("Properties product")
                .that(AMFM_PROPERTIES.getProduct()).isEqualTo(PRODUCT);
    }

    @Test
    public void getVersion_forModuleProperties() {
        mExpect.withMessage("Properties version")
                .that(AMFM_PROPERTIES.getVersion()).isEqualTo(VERSION);
    }

    @Test
    public void getSerial_forModuleProperties() {
        mExpect.withMessage("Serial properties")
                .that(AMFM_PROPERTIES.getSerial()).isEqualTo(SERIAL);
    }

    @Test
    public void getNumTuners_forModuleProperties() {
        mExpect.withMessage("Number of tuners in properties")
                .that(AMFM_PROPERTIES.getNumTuners()).isEqualTo(NUM_TUNERS);
    }

    @Test
    public void getNumAudioSources_forModuleProperties() {
        mExpect.withMessage("Number of audio sources in properties")
                .that(AMFM_PROPERTIES.getNumAudioSources()).isEqualTo(NUM_AUDIO_SOURCES);
    }

    @Test
    public void isInitializationRequired_forModuleProperties() {
        mExpect.withMessage("Initialization required in properties")
                .that(AMFM_PROPERTIES.isInitializationRequired())
                .isEqualTo(IS_INITIALIZATION_REQUIRED);
    }

    @Test
    public void isCaptureSupported_forModuleProperties() {
        mExpect.withMessage("Capture support in properties")
                .that(AMFM_PROPERTIES.isCaptureSupported()).isEqualTo(IS_CAPTURE_SUPPORTED);
    }

    @Test
    public void isBackgroundScanningSupported_forModuleProperties() {
        mExpect.withMessage("Background scan support in properties")
                .that(AMFM_PROPERTIES.isBackgroundScanningSupported())
                .isEqualTo(IS_BG_SCAN_SUPPORTED);
    }

    @Test
    public void isProgramTypeSupported_withSupportedType_forModuleProperties() {
        mExpect.withMessage("AM/FM frequency type radio support in properties")
                .that(AMFM_PROPERTIES.isProgramTypeSupported(
                        ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY))
                .isTrue();
    }

    @Test
    public void isProgramTypeSupported_withNonSupportedType_forModuleProperties() {
        mExpect.withMessage("DAB frequency type radio support in properties")
                .that(AMFM_PROPERTIES.isProgramTypeSupported(
                        ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY)).isFalse();
    }

    @Test
    public void isProgramIdentifierSupported_withSupportedIdentifier_forModuleProperties() {
        mExpect.withMessage("AM/FM frequency identifier radio support in properties")
                .that(AMFM_PROPERTIES.isProgramIdentifierSupported(
                        ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY)).isTrue();
    }

    @Test
    public void isProgramIdentifierSupported_withNonSupportedIdentifier_forModuleProperties() {
        mExpect.withMessage("DAB frequency identifier radio support in properties")
                .that(AMFM_PROPERTIES.isProgramIdentifierSupported(
                        ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY)).isFalse();
    }

    @Test
    public void getDabFrequencyTable_forModulePropertiesInitializedWithNullTable() {
        mExpect.withMessage("Properties DAB frequency table")
                .that(AMFM_PROPERTIES.getDabFrequencyTable()).isNull();
    }

    @Test
    public void getDabFrequencyTable_forModulePropertiesInitializedWithEmptyTable() {
        RadioManager.ModuleProperties properties = createAmFmProperties(new ArrayMap<>());

        mExpect.withMessage("Properties DAB frequency table")
                .that(properties.getDabFrequencyTable()).isNull();
    }

    @Test
    public void getVendorInfo_forModuleProperties() {
        mExpect.withMessage("Properties vendor info")
                .that(AMFM_PROPERTIES.getVendorInfo()).isEmpty();
    }

    @Test
    public void getBands_forModuleProperties() {
        mExpect.withMessage("Properties bands")
                .that(AMFM_PROPERTIES.getBands()).asList()
                .containsExactly(AM_BAND_DESCRIPTOR, FM_BAND_DESCRIPTOR);
    }

    @Test
    public void describeContents_forModuleProperties() {
        mExpect.withMessage("Module properties contents")
                .that(AMFM_PROPERTIES.describeContents()).isEqualTo(0);
    }

    @Test
    public void toString_forModuleProperties() {
        mExpect.withMessage("Module properties string").that(AMFM_PROPERTIES.toString())
                .contains(AM_BAND_DESCRIPTOR.toString() + ", " + FM_BAND_DESCRIPTOR.toString());
    }

    @Test
    public void writeToParcel_forModulePropertiesWithNullDabFrequencyTable() {
        Parcel parcel = Parcel.obtain();

        AMFM_PROPERTIES.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        RadioManager.ModuleProperties modulePropertiesFromParcel =
                RadioManager.ModuleProperties.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("Module properties created from parcel")
                .that(modulePropertiesFromParcel).isEqualTo(AMFM_PROPERTIES);
    }

    @Test
    public void writeToParcel_forModulePropertiesWithNonnullDabFrequencyTable() {
        Parcel parcel = Parcel.obtain();
        RadioManager.ModuleProperties propertiesToParcel = createAmFmProperties(
                Map.of("5A", 174928, "12D", 229072));

        propertiesToParcel.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        RadioManager.ModuleProperties modulePropertiesFromParcel =
                RadioManager.ModuleProperties.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("Module properties created from parcel")
                .that(modulePropertiesFromParcel).isEqualTo(propertiesToParcel);
    }

    @Test
    public void equals_withSameProperties_returnsTrue() {
        RadioManager.ModuleProperties propertiesCompared =
                createAmFmProperties(/* dabFrequencyTable= */ null);

        mExpect.withMessage("The same module properties")
                .that(AMFM_PROPERTIES).isEqualTo(propertiesCompared);
    }

    @Test
    public void equals_withModulePropertiesOfDifferentIds_returnsFalse() {
        RadioManager.ModuleProperties propertiesDab = new RadioManager.ModuleProperties(
                PROPERTIES_ID + 1, SERVICE_NAME, CLASS_ID, IMPLEMENTOR, PRODUCT, VERSION,
                SERIAL, NUM_TUNERS, NUM_AUDIO_SOURCES, IS_INITIALIZATION_REQUIRED,
                IS_CAPTURE_SUPPORTED, /* bands= */ null, IS_BG_SCAN_SUPPORTED,
                SUPPORTED_PROGRAM_TYPES, SUPPORTED_IDENTIFIERS_TYPES, Map.of("5A", 174928),
                /* vendorInfo= */ null);

        mExpect.withMessage("Module properties of different id")
                .that(AMFM_PROPERTIES).isNotEqualTo(propertiesDab);
    }

    @Test
    public void hashCode_withSameModuleProperties_equals() {
        RadioManager.ModuleProperties propertiesCompared =
                createAmFmProperties(/* dabFrequencyTable= */ null);

        mExpect.withMessage("Hash code of the same module properties")
                .that(propertiesCompared.hashCode()).isEqualTo(AMFM_PROPERTIES.hashCode());
    }

    @Test
    public void newArray_forModulePropertiesCreator() {
        RadioManager.ModuleProperties[] modulePropertiesArray =
                RadioManager.ModuleProperties.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("Module properties array")
                .that(modulePropertiesArray).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void getSelector_forProgramInfo() {
        mExpect.withMessage("Selector of DAB program info")
                .that(DAB_PROGRAM_INFO.getSelector()).isEqualTo(DAB_SELECTOR);
    }

    @Test
    public void getLogicallyTunedTo_forProgramInfo() {
        mExpect.withMessage("Identifier logically tuned to in DAB program info")
                .that(DAB_PROGRAM_INFO.getLogicallyTunedTo()).isEqualTo(DAB_SID_EXT_IDENTIFIER);
    }

    @Test
    public void getPhysicallyTunedTo_forProgramInfo() {
        mExpect.withMessage("Identifier physically tuned to DAB program info")
                .that(DAB_PROGRAM_INFO.getPhysicallyTunedTo()).isEqualTo(DAB_FREQUENCY_IDENTIFIER);
    }

    @Test
    public void getRelatedContent_forProgramInfo() {
        mExpect.withMessage("DAB program info contents")
                .that(DAB_PROGRAM_INFO.getRelatedContent())
                .containsExactly(DAB_SID_EXT_IDENTIFIER_RELATED);
    }

    @Test
    public void getChannel_forProgramInfo() {
        mExpect.withMessage("Main channel of DAB program info")
                .that(DAB_PROGRAM_INFO.getChannel()).isEqualTo(0);
    }

    @Test
    public void getSubChannel_forProgramInfo() {
        mExpect.withMessage("Sub channel of DAB program info")
                .that(DAB_PROGRAM_INFO.getSubChannel()).isEqualTo(0);
    }

    @Test
    public void isTuned_forProgramInfo() {
        mExpect.withMessage("Tuned status of DAB program info")
                .that(DAB_PROGRAM_INFO.isTuned()).isTrue();
    }

    @Test
    public void isStereo_forProgramInfo() {
        mExpect.withMessage("Stereo support in DAB program info")
                .that(DAB_PROGRAM_INFO.isStereo()).isTrue();
    }

    @Test
    public void isDigital_forProgramInfo() {
        mExpect.withMessage("Digital DAB program info")
                .that(DAB_PROGRAM_INFO.isDigital()).isTrue();
    }

    @Test
    public void isLive_forProgramInfo() {
        mExpect.withMessage("Live status of DAB program info")
                .that(DAB_PROGRAM_INFO.isLive()).isTrue();
    }

    @Test
    public void isMuted_forProgramInfo() {
        mExpect.withMessage("Muted status of DAB program info")
                .that(DAB_PROGRAM_INFO.isMuted()).isFalse();
    }

    @Test
    public void isTrafficProgram_forProgramInfo() {
        mExpect.withMessage("Traffic program support in DAB program info")
                .that(DAB_PROGRAM_INFO.isTrafficProgram()).isFalse();
    }

    @Test
    public void isTrafficAnnouncementActive_forProgramInfo() {
        mExpect.withMessage("Active traffic announcement for DAB program info")
                .that(DAB_PROGRAM_INFO.isTrafficAnnouncementActive()).isFalse();
    }

    @Test
    public void isSignalAcquired_forProgramInfo() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);

        mExpect.withMessage("Signal acquisition status for HD program info")
                .that(HD_PROGRAM_INFO.isSignalAcquired()).isTrue();
    }

    @Test
    public void isHdSisAvailable_forProgramInfo() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);

        mExpect.withMessage("SIS information acquisition status for HD program")
                .that(HD_PROGRAM_INFO.isHdSisAvailable()).isTrue();
    }

    @Test
    public void isHdAudioAvailable_forProgramInfo() {
        mSetFlagsRule.enableFlags(Flags.FLAG_HD_RADIO_IMPROVED);

        mExpect.withMessage("Audio acquisition status for HD program")
                .that(HD_PROGRAM_INFO.isHdAudioAvailable()).isFalse();
    }

    @Test
    public void getSignalStrength_forProgramInfo() {
        mExpect.withMessage("Signal strength of DAB program info")
                .that(DAB_PROGRAM_INFO.getSignalStrength()).isEqualTo(SIGNAL_QUALITY);
    }

    @Test
    public void getMetadata_forProgramInfo() {
        mExpect.withMessage("Metadata of DAB program info")
                .that(DAB_PROGRAM_INFO.getMetadata()).isEqualTo(METADATA);
    }

    @Test
    public void getVendorInfo_forProgramInfo() {
        mExpect.withMessage("Vendor info of DAB program info")
                .that(DAB_PROGRAM_INFO.getVendorInfo()).isEmpty();
    }

    @Test
    public void describeContents_forProgramInfo() {
        mExpect.withMessage("Program info contents")
                .that(DAB_PROGRAM_INFO.describeContents()).isEqualTo(0);
    }

    @Test
    public void newArray_forProgramInfoCreator() {
        RadioManager.ProgramInfo[] programInfoArray =
                RadioManager.ProgramInfo.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("Program infos").that(programInfoArray).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void writeToParcel_forProgramInfo() {
        Parcel parcel = Parcel.obtain();

        DAB_PROGRAM_INFO.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        RadioManager.ProgramInfo programInfoFromParcel =
                RadioManager.ProgramInfo.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("Program info created from parcel")
                .that(programInfoFromParcel).isEqualTo(DAB_PROGRAM_INFO);
    }

    @Test
    public void equals_withSameProgramInfo_returnsTrue() {
        RadioManager.ProgramInfo dabProgramInfoCompared = createDabProgramInfo(DAB_SELECTOR);

        mExpect.withMessage("The same program info")
                .that(dabProgramInfoCompared).isEqualTo(DAB_PROGRAM_INFO);
    }

    @Test
    public void equals_withSameProgramInfoOfDifferentSecondaryIdSelectors_returnsFalse() {
        ProgramSelector dabSelectorCompared = new ProgramSelector(
                ProgramSelector.PROGRAM_TYPE_DAB, DAB_SID_EXT_IDENTIFIER,
                new ProgramSelector.Identifier[]{DAB_FREQUENCY_IDENTIFIER},
                /* vendorIds= */ null);
        RadioManager.ProgramInfo dabProgramInfoCompared = createDabProgramInfo(dabSelectorCompared);

        mExpect.withMessage("Program info with different secondary id selectors")
                .that(DAB_PROGRAM_INFO).isNotEqualTo(dabProgramInfoCompared);
    }

    @Test
    public void listModules_forRadioManager() throws Exception {
        createRadioManager();
        List<RadioManager.ModuleProperties> modules = new ArrayList<>();

        mRadioManager.listModules(modules);

        mExpect.withMessage("Modules in radio manager")
                .that(modules).containsExactly(AMFM_PROPERTIES);
    }

    @Test
    public void listModules_forRadioManagerWithNullListAsInput_fails() throws Exception {
        createRadioManager();

        mExpect.withMessage("Status when listing module with empty list input")
                .that(mRadioManager.listModules(null)).isEqualTo(RadioManager.STATUS_BAD_VALUE);
    }

    @Test
    public void listModules_withNullListFromService_fails() throws Exception {
        createRadioManager();
        when(mRadioServiceMock.listModules()).thenReturn(null);
        List<RadioManager.ModuleProperties> modules = new ArrayList<>();

        mExpect.withMessage("Status for listing module when getting null list from HAL client")
                .that(mRadioManager.listModules(modules)).isEqualTo(RadioManager.STATUS_ERROR);
    }

    @Test
    public void listModules_whenServiceDied_fails() throws Exception {
        createRadioManager();
        when(mRadioServiceMock.listModules()).thenThrow(new RemoteException());
        List<RadioManager.ModuleProperties> modules = new ArrayList<>();

        mExpect.withMessage("Status for listing module when HAL client service is dead")
                .that(mRadioManager.listModules(modules))
                .isEqualTo(RadioManager.STATUS_DEAD_OBJECT);
    }

    @Test
    public void openTuner_forRadioModule() throws Exception {
        createRadioManager();
        int moduleId = 0;
        boolean withAudio = true;

        mRadioManager.openTuner(moduleId, FM_BAND_CONFIG, withAudio, mCallbackMock,
                /* handler= */ null);

        verify(mRadioServiceMock).openTuner(eq(moduleId), eq(FM_BAND_CONFIG), eq(withAudio), any());
    }

    @Test
    public void openTuner_whenServiceDied_returnsNull() throws Exception {
        createRadioManager();
        when(mRadioServiceMock.openTuner(anyInt(), any(), anyBoolean(), any()))
                .thenThrow(new RemoteException());

        RadioTuner nullTuner = mRadioManager.openTuner(/* moduleId= */ 0, FM_BAND_CONFIG,
                /* withAudio= */ true, mCallbackMock, /* handler= */ null);

        mExpect.withMessage("Radio tuner when service is dead").that(nullTuner).isNull();
    }

    @Test
    public void openTuner_withNullCallback() throws Exception {
        createRadioManager();
        int moduleId = 0;
        boolean withAudio = true;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mRadioManager.openTuner(moduleId, FM_BAND_CONFIG, withAudio,
                        /* callback= */ null, /* handler= */ null));

        mExpect.withMessage("Null tuner callback exception").that(thrown)
                .hasMessageThat().contains("callback must not be empty");
    }

    @Test
    public void addAnnouncementListener_withListenerNotAddedBefore() throws Exception {
        createRadioManager();
        Set<Integer> enableTypeSet = createAnnouncementTypeSet(EVENT_ANNOUNCEMENT_TYPE);
        int[] enableTypesExpected = new int[]{EVENT_ANNOUNCEMENT_TYPE};
        ArgumentCaptor<IAnnouncementListener> announcementListener =
                ArgumentCaptor.forClass(IAnnouncementListener.class);

        mRadioManager.addAnnouncementListener(enableTypeSet, mEventListener);

        verify(mRadioServiceMock).addAnnouncementListener(eq(enableTypesExpected),
                announcementListener.capture());

        announcementListener.getValue().onListUpdated(TEST_ANNOUNCEMENT_LIST);

        verify(mEventListener).onListUpdated(TEST_ANNOUNCEMENT_LIST);
    }

    @Test
    public void addAnnouncementListener_withListenerAddedBefore_closesPreviousOne()
            throws Exception {
        createRadioManager();
        Set<Integer> enableTypeSet = createAnnouncementTypeSet(EVENT_ANNOUNCEMENT_TYPE);
        mRadioManager.addAnnouncementListener(enableTypeSet, mEventListener);

        mRadioManager.addAnnouncementListener(enableTypeSet, mEventListener);

        verify(mCloseHandleMock).close();
    }

    @Test
    public void addAnnouncementListener_withListenerAddedBeforeAndCloseException_throws()
            throws Exception {
        createRadioManager();
        Set<Integer> enableTypeSet = createAnnouncementTypeSet(EVENT_ANNOUNCEMENT_TYPE);
        mRadioManager.addAnnouncementListener(enableTypeSet, mEventListener);
        doThrow(new RemoteException()).when(mCloseHandleMock).close();

        assertThrows(RuntimeException.class,
                () -> mRadioManager.addAnnouncementListener(enableTypeSet, mEventListener));
    }

    @Test
    public void addAnnouncementListener_whenServiceDied_throwException() throws Exception {
        createRadioManager();
        String exceptionMessage = "service is dead";
        when(mRadioServiceMock.addAnnouncementListener(any(), any()))
                .thenThrow(new RemoteException(exceptionMessage));
        Set<Integer> enableTypeSet = createAnnouncementTypeSet(EVENT_ANNOUNCEMENT_TYPE);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> mRadioManager.addAnnouncementListener(enableTypeSet, mEventListener));

        mExpect.withMessage("Exception for adding announcement listener with dead service")
                .that(thrown).hasMessageThat().contains(exceptionMessage);
    }

    @Test
    public void removeAnnouncementListener_withListenerNotAddedBefore_ignores() throws Exception {
        createRadioManager();

        mRadioManager.removeAnnouncementListener(mEventListener);

        verify(mCloseHandleMock, never()).close();
    }

    @Test
    public void removeAnnouncementListener_withListenerAddedTwice_closesTheFirstOne()
            throws Exception {
        createRadioManager();
        Set<Integer> enableTypeSet = createAnnouncementTypeSet(EVENT_ANNOUNCEMENT_TYPE);
        mRadioManager.addAnnouncementListener(enableTypeSet, mEventListener);

        mRadioManager.removeAnnouncementListener(mEventListener);

        verify(mCloseHandleMock).close();
    }

    private static RadioManager.ModuleProperties createAmFmProperties(
            @Nullable Map<String, Integer>  dabFrequencyTable) {
        return new RadioManager.ModuleProperties(PROPERTIES_ID, SERVICE_NAME, CLASS_ID,
                IMPLEMENTOR, PRODUCT, VERSION, SERIAL, NUM_TUNERS, NUM_AUDIO_SOURCES,
                IS_INITIALIZATION_REQUIRED, IS_CAPTURE_SUPPORTED,
                new RadioManager.BandDescriptor[]{AM_BAND_DESCRIPTOR, FM_BAND_DESCRIPTOR},
                IS_BG_SCAN_SUPPORTED, SUPPORTED_PROGRAM_TYPES, SUPPORTED_IDENTIFIERS_TYPES,
                dabFrequencyTable, /* vendorInfo= */ null);
    }

    private static RadioManager.FmBandDescriptor createFmBandDescriptor() {
        return new RadioManager.FmBandDescriptor(REGION, RadioManager.BAND_FM, FM_LOWER_LIMIT,
                FM_UPPER_LIMIT, FM_SPACING, STEREO_SUPPORTED, RDS_SUPPORTED, TA_SUPPORTED,
                AF_SUPPORTED, EA_SUPPORTED);
    }

    private static RadioManager.AmBandDescriptor createAmBandDescriptor() {
        return new RadioManager.AmBandDescriptor(REGION, RadioManager.BAND_AM, AM_LOWER_LIMIT,
                AM_UPPER_LIMIT, AM_SPACING, STEREO_SUPPORTED);
    }

    private static RadioManager.FmBandConfig createFmBandConfig() {
        return new RadioManager.FmBandConfig(createFmBandDescriptor());
    }

    private static RadioManager.AmBandConfig createAmBandConfig() {
        return new RadioManager.AmBandConfig(createAmBandDescriptor());
    }

    private static RadioMetadata createMetadata() {
        RadioMetadata.Builder metadataBuilder = new RadioMetadata.Builder();
        return metadataBuilder.putString(RadioMetadata.METADATA_KEY_ARTIST, "artistTest").build();
    }

    private static RadioManager.ProgramInfo createDabProgramInfo(ProgramSelector selector) {
        return new RadioManager.ProgramInfo(selector, selector.getPrimaryId(),
                DAB_FREQUENCY_IDENTIFIER, Arrays.asList(DAB_SID_EXT_IDENTIFIER_RELATED),
                INFO_FLAGS_DAB, SIGNAL_QUALITY, METADATA, /* vendorInfo= */ null);
    }

    private static RadioManager.ProgramInfo createHdProgramInfo(ProgramSelector selector) {
        long frequency = (selector.getPrimaryId().getValue() >> 32);
        ProgramSelector.Identifier physicallyTunedToId = new ProgramSelector.Identifier(
                ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, frequency);
        return new RadioManager.ProgramInfo(selector, selector.getPrimaryId(), physicallyTunedToId,
                Collections.emptyList(), INFO_FLAGS_HD, SIGNAL_QUALITY, METADATA,
                /* vendorInfo= */ null);
    }

    private void createRadioManager() throws RemoteException {
        when(mContextMock.getApplicationInfo()).thenReturn(mApplicationInfo);
        when(mRadioServiceMock.listModules()).thenReturn(Arrays.asList(AMFM_PROPERTIES));
        when(mRadioServiceMock.addAnnouncementListener(any(), any())).thenReturn(mCloseHandleMock);

        mRadioManager = new RadioManager(mContextMock, mRadioServiceMock);
    }

    private Set<Integer> createAnnouncementTypeSet(int enableType) {
        return Set.of(enableType);
    }
}
