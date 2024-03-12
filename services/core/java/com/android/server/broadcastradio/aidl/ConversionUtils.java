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

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.hardware.broadcastradio.AmFmRegionConfig;
import android.hardware.broadcastradio.Announcement;
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
import android.hardware.radio.Flags;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;
import android.hardware.radio.RadioTuner;
import android.hardware.radio.UniqueProgramIdentifier;
import android.os.Build;
import android.os.ParcelableException;
import android.os.ServiceSpecificException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.Slogf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A utils class converting data types between AIDL broadcast radio HAL and
 * {@link android.hardware.radio}
 */
final class ConversionUtils {
    private static final String TAG = "BcRadioAidlSrv.convert";

    /**
     * With RADIO_U_VERSION_REQUIRED enabled, 44-bit DAB identifier
     * {@code IdentifierType#DAB_SID_EXT} from broadcast radio HAL can be passed as
     * {@code ProgramSelector#IDENTIFIER_TYPE_DAB_DMB_SID_EXT} to {@code RadioTuner}.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final long RADIO_U_VERSION_REQUIRED = 261770108L;

    /**
     * With RADIO_V_VERSION_REQUIRED enabled, identifier types, config flags and metadata added
     * in V for HD radio can be passed to {@code RadioTuner} by
     * {@code android.hardware.radio.ITunerCallback}
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public static final long RADIO_V_VERSION_REQUIRED = 302589903L;

    private ConversionUtils() {
        throw new UnsupportedOperationException("ConversionUtils class is noninstantiable");
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    static boolean isAtLeastU(int uid) {
        return CompatChanges.isChangeEnabled(RADIO_U_VERSION_REQUIRED, uid);
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    static boolean isAtLeastV(int uid) {
        return CompatChanges.isChangeEnabled(RADIO_V_VERSION_REQUIRED, uid);
    }

    static RuntimeException throwOnError(RuntimeException halException, String action) {
        if (!(halException instanceof ServiceSpecificException)) {
            return new ParcelableException(new RuntimeException(
                    action + ": unknown error"));
        }
        int result = ((ServiceSpecificException) halException).errorCode;
        switch (result) {
            case Result.UNKNOWN_ERROR:
                return new ParcelableException(new RuntimeException(action
                        + ": UNKNOWN_ERROR"));
            case Result.INTERNAL_ERROR:
                return new ParcelableException(new RuntimeException(action
                        + ": INTERNAL_ERROR"));
            case Result.INVALID_ARGUMENTS:
                return new IllegalArgumentException(action + ": INVALID_ARGUMENTS");
            case Result.INVALID_STATE:
                return new IllegalStateException(action + ": INVALID_STATE");
            case Result.NOT_SUPPORTED:
                return new UnsupportedOperationException(action + ": NOT_SUPPORTED");
            case Result.TIMEOUT:
                return new ParcelableException(new RuntimeException(action + ": TIMEOUT"));
            case Result.CANCELED:
                return new IllegalStateException(action + ": CANCELED");
            default:
                return new ParcelableException(new RuntimeException(
                        action + ": unknown error (" + result + ")"));
        }
    }

    @RadioTuner.TunerResultType
    static int halResultToTunerResult(int result) {
        switch (result) {
            case Result.OK:
                return RadioTuner.TUNER_RESULT_OK;
            case Result.INTERNAL_ERROR:
                return RadioTuner.TUNER_RESULT_INTERNAL_ERROR;
            case Result.INVALID_ARGUMENTS:
                return RadioTuner.TUNER_RESULT_INVALID_ARGUMENTS;
            case Result.INVALID_STATE:
                return RadioTuner.TUNER_RESULT_INVALID_STATE;
            case Result.NOT_SUPPORTED:
                return RadioTuner.TUNER_RESULT_NOT_SUPPORTED;
            case Result.TIMEOUT:
                return RadioTuner.TUNER_RESULT_TIMEOUT;
            case Result.CANCELED:
                return RadioTuner.TUNER_RESULT_CANCELED;
            case Result.UNKNOWN_ERROR:
            default:
                return RadioTuner.TUNER_RESULT_UNKNOWN_ERROR;
        }
    }

    static VendorKeyValue[] vendorInfoToHalVendorKeyValues(@Nullable Map<String, String> info) {
        if (info == null) {
            return new VendorKeyValue[]{};
        }

        ArrayList<VendorKeyValue> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : info.entrySet()) {
            VendorKeyValue elem = new VendorKeyValue();
            elem.key = entry.getKey();
            elem.value = entry.getValue();
            if (elem.key == null || elem.value == null) {
                Slogf.w(TAG, "VendorKeyValue contains invalid entry: key = %s, value = %s",
                        elem.key, elem.value);
                continue;
            }
            list.add(elem);
        }

        return list.toArray(VendorKeyValue[]::new);
    }

    static Map<String, String> vendorInfoFromHalVendorKeyValues(@Nullable VendorKeyValue[] info) {
        if (info == null) {
            return Collections.emptyMap();
        }

        Map<String, String> map = new ArrayMap<>();
        for (VendorKeyValue kvp : info) {
            if (kvp.key == null || kvp.value == null) {
                Slogf.w(TAG, "VendorKeyValue contains invalid entry: key = %s, value = %s",
                        kvp.key, kvp.value);
                continue;
            }
            map.put(kvp.key, kvp.value);
        }

        return map;
    }

    @ProgramSelector.ProgramType
    private static int identifierTypeToProgramType(
            @ProgramSelector.IdentifierType int idType) {
        switch (idType) {
            case ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY:
            case ProgramSelector.IDENTIFIER_TYPE_RDS_PI:
                // TODO(b/69958423): verify AM/FM with frequency range
                return ProgramSelector.PROGRAM_TYPE_FM;
            case ProgramSelector.IDENTIFIER_TYPE_HD_STATION_ID_EXT:
            case ProgramSelector.IDENTIFIER_TYPE_HD_STATION_NAME:
                // TODO(b/69958423): verify AM/FM with frequency range
                return ProgramSelector.PROGRAM_TYPE_FM_HD;
            case ProgramSelector.IDENTIFIER_TYPE_DAB_SIDECC:
            case ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE:
            case ProgramSelector.IDENTIFIER_TYPE_DAB_SCID:
            case ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY:
            case ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT:
                return ProgramSelector.PROGRAM_TYPE_DAB;
            case ProgramSelector.IDENTIFIER_TYPE_DRMO_SERVICE_ID:
            case ProgramSelector.IDENTIFIER_TYPE_DRMO_FREQUENCY:
                return ProgramSelector.PROGRAM_TYPE_DRMO;
            case ProgramSelector.IDENTIFIER_TYPE_SXM_SERVICE_ID:
            case ProgramSelector.IDENTIFIER_TYPE_SXM_CHANNEL:
                return ProgramSelector.PROGRAM_TYPE_SXM;
            default:
                if (Flags.hdRadioImproved()) {
                    if (idType == ProgramSelector.IDENTIFIER_TYPE_HD_STATION_LOCATION) {
                        return ProgramSelector.PROGRAM_TYPE_FM_HD;
                    }
                }
        }
        if (idType >= ProgramSelector.IDENTIFIER_TYPE_VENDOR_PRIMARY_START
                && idType <= ProgramSelector.IDENTIFIER_TYPE_VENDOR_PRIMARY_END) {
            return idType;
        }
        return ProgramSelector.PROGRAM_TYPE_INVALID;
    }

    private static int[] identifierTypesToProgramTypes(int[] idTypes) {
        Set<Integer> programTypes = new ArraySet<>();

        for (int i = 0; i < idTypes.length; i++) {
            int pType = identifierTypeToProgramType(idTypes[i]);

            if (pType == ProgramSelector.PROGRAM_TYPE_INVALID) continue;

            programTypes.add(pType);
            if (pType == ProgramSelector.PROGRAM_TYPE_FM) {
                // TODO(b/69958423): verify AM/FM with region info
                programTypes.add(ProgramSelector.PROGRAM_TYPE_AM);
            }
            if (pType == ProgramSelector.PROGRAM_TYPE_FM_HD) {
                // TODO(b/69958423): verify AM/FM with region info
                programTypes.add(ProgramSelector.PROGRAM_TYPE_AM_HD);
            }
        }

        int[] programTypesArray = new int[programTypes.size()];
        int i = 0;
        for (int programType : programTypes) {
            programTypesArray[i++] = programType;
        }
        return programTypesArray;
    }

    private static RadioManager.BandDescriptor[] amfmConfigToBands(
            @Nullable AmFmRegionConfig config) {
        if (config == null) {
            return new RadioManager.BandDescriptor[0];
        }

        int len = config.ranges.length;
        List<RadioManager.BandDescriptor> bands = new ArrayList<>();

        // Just a placeholder value.
        int region = RadioManager.REGION_ITU_1;

        for (int i = 0; i < len; i++) {
            Utils.FrequencyBand bandType = Utils.getBand(config.ranges[i].lowerBound);
            if (bandType == Utils.FrequencyBand.UNKNOWN) {
                Slogf.e(TAG, "Unknown frequency band at %d kHz", config.ranges[i].lowerBound);
                continue;
            }
            if (bandType == Utils.FrequencyBand.FM) {
                bands.add(new RadioManager.FmBandDescriptor(region, RadioManager.BAND_FM,
                        config.ranges[i].lowerBound, config.ranges[i].upperBound,
                        config.ranges[i].spacing,

                        // TODO(b/69958777): stereo, rds, ta, af, ea
                        /* stereo= */ true, /* rds= */ true, /* ta= */ true, /* af= */ true,
                        /* ea= */ true
                ));
            } else {  // AM
                bands.add(new RadioManager.AmBandDescriptor(region, RadioManager.BAND_AM,
                        config.ranges[i].lowerBound, config.ranges[i].upperBound,
                        config.ranges[i].spacing,

                        // TODO(b/69958777): stereo
                        /* stereo= */ true
                ));
            }
        }

        return bands.toArray(RadioManager.BandDescriptor[]::new);
    }

    @Nullable
    private static Map<String, Integer> dabConfigFromHalDabTableEntries(
            @Nullable DabTableEntry[] config) {
        if (config == null) {
            return null;
        }
        Map<String, Integer> dabConfig = new ArrayMap<>();
        for (int i = 0; i < config.length; i++) {
            dabConfig.put(config[i].label, config[i].frequencyKhz);
        }
        return dabConfig;
    }

    static RadioManager.ModuleProperties propertiesFromHalProperties(int id,
            String serviceName, Properties prop,
            @Nullable AmFmRegionConfig amfmConfig, @Nullable DabTableEntry[] dabConfig) {
        Objects.requireNonNull(serviceName);
        Objects.requireNonNull(prop);

        int[] supportedProgramTypes = identifierTypesToProgramTypes(prop.supportedIdentifierTypes);

        return new RadioManager.ModuleProperties(
                id,
                serviceName,

                // There is no Class concept in HAL AIDL.
                RadioManager.CLASS_AM_FM,

                prop.maker,
                prop.product,
                prop.version,
                prop.serial,

                // HAL AIDL only supports single tuner and audio source per
                // HAL implementation instance.
                /* numTuners= */ 1,
                /* numAudioSources= */ 1,
                /* isInitializationRequired= */ false,
                /* isCaptureSupported= */ false,

                amfmConfigToBands(amfmConfig),
                /* isBgScanSupported= */ true,
                supportedProgramTypes,
                prop.supportedIdentifierTypes,
                dabConfigFromHalDabTableEntries(dabConfig),
                vendorInfoFromHalVendorKeyValues(prop.vendorInfo)
        );
    }

    static ProgramIdentifier identifierToHalProgramIdentifier(ProgramSelector.Identifier id) {
        ProgramIdentifier hwId = new ProgramIdentifier();
        if (id.getType() == ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT) {
            hwId.type = IdentifierType.DAB_SID_EXT;
        } else if (Flags.hdRadioImproved()) {
            if (id.getType() == ProgramSelector.IDENTIFIER_TYPE_HD_STATION_LOCATION) {
                hwId.type = IdentifierType.HD_STATION_LOCATION;
            } else {
                hwId.type = id.getType();
            }
        } else {
            hwId.type = id.getType();
        }
        long value = id.getValue();
        if (id.getType() == ProgramSelector.IDENTIFIER_TYPE_DAB_SID_EXT) {
            hwId.value = (value & 0xFFFF) | ((value >>> 16) << 32);
        } else {
            hwId.value = value;
        }
        return hwId;
    }

    @Nullable
    static ProgramSelector.Identifier identifierFromHalProgramIdentifier(
            ProgramIdentifier id) {
        if (id.type == IdentifierType.INVALID) {
            return null;
        }
        int idType;
        if (id.type == IdentifierType.DAB_SID_EXT) {
            idType = ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT;
        } else if (id.type == IdentifierType.HD_STATION_LOCATION) {
            if (Flags.hdRadioImproved()) {
                idType = ProgramSelector.IDENTIFIER_TYPE_HD_STATION_LOCATION;
            } else {
                return null;
            }
        } else {
            idType = id.type;
        }
        return new ProgramSelector.Identifier(idType, id.value);
    }

    private static boolean isVendorIdentifierType(int idType) {
        return idType >= IdentifierType.VENDOR_START && idType <= IdentifierType.VENDOR_END;
    }

    private static boolean isValidHalProgramSelector(
            android.hardware.broadcastradio.ProgramSelector sel) {
        return sel.primaryId.type == IdentifierType.AMFM_FREQUENCY_KHZ
                || sel.primaryId.type == IdentifierType.RDS_PI
                || sel.primaryId.type == IdentifierType.HD_STATION_ID_EXT
                || sel.primaryId.type == IdentifierType.DAB_SID_EXT
                || sel.primaryId.type == IdentifierType.DRMO_SERVICE_ID
                || sel.primaryId.type == IdentifierType.SXM_SERVICE_ID
                || isVendorIdentifierType(sel.primaryId.type);
    }

    @Nullable
    static android.hardware.broadcastradio.ProgramSelector programSelectorToHalProgramSelector(
            ProgramSelector sel) {
        android.hardware.broadcastradio.ProgramSelector hwSel =
                new android.hardware.broadcastradio.ProgramSelector();

        hwSel.primaryId = identifierToHalProgramIdentifier(sel.getPrimaryId());
        ProgramSelector.Identifier[] secondaryIds = sel.getSecondaryIds();
        ArrayList<ProgramIdentifier> secondaryIdList = new ArrayList<>(secondaryIds.length);
        for (int i = 0; i < secondaryIds.length; i++) {
            ProgramIdentifier hwId = identifierToHalProgramIdentifier(secondaryIds[i]);
            if (hwId.type != IdentifierType.INVALID) {
                secondaryIdList.add(hwId);
            } else {
                Slogf.w(TAG, "Invalid secondary id: %s", secondaryIds[i]);
            }
        }
        hwSel.secondaryIds = secondaryIdList.toArray(ProgramIdentifier[]::new);
        if (!isValidHalProgramSelector(hwSel)) {
            return null;
        }
        return hwSel;
    }

    private static boolean isEmpty(
            android.hardware.broadcastradio.ProgramSelector sel) {
        return sel.primaryId.type == IdentifierType.INVALID && sel.primaryId.value == 0
                && sel.secondaryIds.length == 0;
    }

    @Nullable
    static ProgramSelector programSelectorFromHalProgramSelector(
            android.hardware.broadcastradio.ProgramSelector sel) {
        if (isEmpty(sel) || !isValidHalProgramSelector(sel)) {
            return null;
        }

        List<ProgramSelector.Identifier> secondaryIdList = new ArrayList<>();
        for (int i = 0; i < sel.secondaryIds.length; i++) {
            if (sel.secondaryIds[i] != null) {
                ProgramSelector.Identifier id = identifierFromHalProgramIdentifier(
                        sel.secondaryIds[i]);
                if (id == null) {
                    Slogf.e(TAG, "invalid secondary id: %s", sel.secondaryIds[i]);
                }
                secondaryIdList.add(id);
            }
        }

        return new ProgramSelector(
                identifierTypeToProgramType(sel.primaryId.type),
                Objects.requireNonNull(identifierFromHalProgramIdentifier(sel.primaryId)),
                secondaryIdList.toArray(new ProgramSelector.Identifier[0]),
                /* vendorIds= */ null);
    }

    @VisibleForTesting
    static RadioMetadata radioMetadataFromHalMetadata(Metadata[] meta) {
        RadioMetadata.Builder builder = new RadioMetadata.Builder();

        for (int i = 0; i < meta.length; i++) {
            int tag = meta[i].getTag();
            switch (tag) {
                case Metadata.rdsPs:
                    builder.putString(RadioMetadata.METADATA_KEY_RDS_PS, meta[i].getRdsPs());
                    break;
                case Metadata.rdsPty:
                    builder.putInt(RadioMetadata.METADATA_KEY_RDS_PTY, meta[i].getRdsPty());
                    break;
                case Metadata.rbdsPty:
                    builder.putInt(RadioMetadata.METADATA_KEY_RBDS_PTY, meta[i].getRbdsPty());
                    break;
                case Metadata.rdsRt:
                    builder.putString(RadioMetadata.METADATA_KEY_RDS_RT, meta[i].getRdsRt());
                    break;
                case Metadata.songTitle:
                    builder.putString(RadioMetadata.METADATA_KEY_TITLE, meta[i].getSongTitle());
                    break;
                case Metadata.songArtist:
                    builder.putString(RadioMetadata.METADATA_KEY_ARTIST, meta[i].getSongArtist());
                    break;
                case Metadata.songAlbum:
                    builder.putString(RadioMetadata.METADATA_KEY_ALBUM, meta[i].getSongAlbum());
                    break;
                case Metadata.stationIcon:
                    builder.putInt(RadioMetadata.METADATA_KEY_ICON, meta[i].getStationIcon());
                    break;
                case Metadata.albumArt:
                    builder.putInt(RadioMetadata.METADATA_KEY_ART, meta[i].getAlbumArt());
                    break;
                case Metadata.programName:
                    builder.putString(RadioMetadata.METADATA_KEY_PROGRAM_NAME,
                            meta[i].getProgramName());
                    break;
                case Metadata.dabEnsembleName:
                    builder.putString(RadioMetadata.METADATA_KEY_DAB_ENSEMBLE_NAME,
                            meta[i].getDabEnsembleName());
                    break;
                case Metadata.dabEnsembleNameShort:
                    builder.putString(RadioMetadata.METADATA_KEY_DAB_ENSEMBLE_NAME_SHORT,
                            meta[i].getDabEnsembleNameShort());
                    break;
                case Metadata.dabServiceName:
                    builder.putString(RadioMetadata.METADATA_KEY_DAB_SERVICE_NAME,
                            meta[i].getDabServiceName());
                    break;
                case Metadata.dabServiceNameShort:
                    builder.putString(RadioMetadata.METADATA_KEY_DAB_SERVICE_NAME_SHORT,
                            meta[i].getDabServiceNameShort());
                    break;
                case Metadata.dabComponentName:
                    builder.putString(RadioMetadata.METADATA_KEY_DAB_COMPONENT_NAME,
                            meta[i].getDabComponentName());
                    break;
                case Metadata.dabComponentNameShort:
                    builder.putString(RadioMetadata.METADATA_KEY_DAB_COMPONENT_NAME_SHORT,
                            meta[i].getDabComponentNameShort());
                    break;
                default:
                    if (Flags.hdRadioImproved()) {
                        switch (tag) {
                            case Metadata.genre:
                                builder.putString(RadioMetadata.METADATA_KEY_GENRE,
                                        meta[i].getGenre());
                                break;
                            case Metadata.commentShortDescription:
                                builder.putString(
                                        RadioMetadata.METADATA_KEY_COMMENT_SHORT_DESCRIPTION,
                                        meta[i].getCommentShortDescription());
                                break;
                            case Metadata.commentActualText:
                                builder.putString(RadioMetadata.METADATA_KEY_COMMENT_ACTUAL_TEXT,
                                        meta[i].getCommentActualText());
                                break;
                            case Metadata.commercial:
                                builder.putString(RadioMetadata.METADATA_KEY_COMMERCIAL,
                                        meta[i].getCommercial());
                                break;
                            case Metadata.ufids:
                                builder.putStringArray(RadioMetadata.METADATA_KEY_UFIDS,
                                        meta[i].getUfids());
                                break;
                            case Metadata.hdStationNameShort:
                                builder.putString(RadioMetadata.METADATA_KEY_HD_STATION_NAME_SHORT,
                                        meta[i].getHdStationNameShort());
                                break;
                            case Metadata.hdStationNameLong:
                                builder.putString(RadioMetadata.METADATA_KEY_HD_STATION_NAME_LONG,
                                        meta[i].getHdStationNameLong());
                                break;
                            case Metadata.hdSubChannelsAvailable:
                                builder.putInt(RadioMetadata.METADATA_KEY_HD_SUBCHANNELS_AVAILABLE,
                                        meta[i].getHdSubChannelsAvailable());
                                break;
                            default:
                                Slogf.w(TAG, "Ignored unknown metadata entry: %s with HD radio flag"
                                        + " enabled", meta[i]);
                                break;
                        }
                    } else {
                        Slogf.w(TAG, "Ignored unknown metadata entry: %s with HD radio flag "
                                + "disabled", meta[i]);
                    }
                    break;
            }
        }

        return builder.build();
    }

    private static boolean isValidLogicallyTunedTo(ProgramIdentifier id) {
        return id.type == IdentifierType.AMFM_FREQUENCY_KHZ || id.type == IdentifierType.RDS_PI
                || id.type == IdentifierType.HD_STATION_ID_EXT
                || id.type == IdentifierType.DAB_SID_EXT
                || id.type == IdentifierType.DRMO_SERVICE_ID
                || id.type == IdentifierType.SXM_SERVICE_ID
                || isVendorIdentifierType(id.type);
    }

    private static boolean isValidPhysicallyTunedTo(ProgramIdentifier id) {
        return id.type == IdentifierType.AMFM_FREQUENCY_KHZ
                || id.type == IdentifierType.DAB_FREQUENCY_KHZ
                || id.type == IdentifierType.DRMO_FREQUENCY_KHZ
                || id.type == IdentifierType.SXM_CHANNEL
                || isVendorIdentifierType(id.type);
    }

    private static boolean isValidHalProgramInfo(ProgramInfo info) {
        return isValidHalProgramSelector(info.selector)
                && isValidLogicallyTunedTo(info.logicallyTunedTo)
                && isValidPhysicallyTunedTo(info.physicallyTunedTo);
    }

    @Nullable
    static RadioManager.ProgramInfo programInfoFromHalProgramInfo(ProgramInfo info) {
        if (!isValidHalProgramInfo(info)) {
            return null;
        }
        Collection<ProgramSelector.Identifier> relatedContent = new ArrayList<>();
        if (info.relatedContent != null) {
            for (int i = 0; i < info.relatedContent.length; i++) {
                ProgramSelector.Identifier relatedContentId =
                        identifierFromHalProgramIdentifier(info.relatedContent[i]);
                if (relatedContentId != null) {
                    relatedContent.add(relatedContentId);
                }
            }
        }

        return new RadioManager.ProgramInfo(
                Objects.requireNonNull(programSelectorFromHalProgramSelector(info.selector)),
                identifierFromHalProgramIdentifier(info.logicallyTunedTo),
                identifierFromHalProgramIdentifier(info.physicallyTunedTo),
                relatedContent,
                info.infoFlags,
                info.signalQuality,
                radioMetadataFromHalMetadata(info.metadata),
                vendorInfoFromHalVendorKeyValues(info.vendorInfo)
        );
    }

    static ProgramFilter filterToHalProgramFilter(@Nullable ProgramList.Filter filter) {
        if (filter == null) {
            filter = new ProgramList.Filter();
        }

        ProgramFilter hwFilter = new ProgramFilter();

        IntArray identifierTypeList = new IntArray(filter.getIdentifierTypes().size());
        ArrayList<ProgramIdentifier> identifiersList = new ArrayList<>();
        Iterator<Integer> typeIterator = filter.getIdentifierTypes().iterator();
        while (typeIterator.hasNext()) {
            identifierTypeList.add(typeIterator.next());
        }
        Iterator<ProgramSelector.Identifier> idIterator = filter.getIdentifiers().iterator();
        while (idIterator.hasNext()) {
            ProgramSelector.Identifier id = idIterator.next();
            ProgramIdentifier hwId = identifierToHalProgramIdentifier(id);
            if (hwId.type != IdentifierType.INVALID) {
                identifiersList.add(hwId);
            } else {
                Slogf.w(TAG, "Invalid identifiers: %s", id);
            }
        }

        hwFilter.identifierTypes = identifierTypeList.toArray();
        hwFilter.identifiers = identifiersList.toArray(ProgramIdentifier[]::new);
        hwFilter.includeCategories = filter.areCategoriesIncluded();
        hwFilter.excludeModifications = filter.areModificationsExcluded();

        return hwFilter;
    }

    private static boolean identifierMeetsSdkVersionRequirement(ProgramSelector.Identifier id,
            int uid) {
        if (Flags.hdRadioImproved() && !isAtLeastV(uid)) {
            if (id.getType() == ProgramSelector.IDENTIFIER_TYPE_HD_STATION_LOCATION) {
                return false;
            }
        }
        if (!isAtLeastU(uid)) {
            return id.getType() != ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT;
        }
        return true;
    }

    static boolean programSelectorMeetsSdkVersionRequirement(ProgramSelector sel, int uid) {
        if (!identifierMeetsSdkVersionRequirement(sel.getPrimaryId(), uid)) {
            return false;
        }
        ProgramSelector.Identifier[] secondaryIds = sel.getSecondaryIds();
        for (int i = 0; i < secondaryIds.length; i++) {
            if (!identifierMeetsSdkVersionRequirement(secondaryIds[i], uid)) {
                return false;
            }
        }
        return true;
    }

    static boolean programInfoMeetsSdkVersionRequirement(RadioManager.ProgramInfo info, int uid) {
        if (!programSelectorMeetsSdkVersionRequirement(info.getSelector(), uid)) {
            return false;
        }
        if (!identifierMeetsSdkVersionRequirement(info.getLogicallyTunedTo(), uid)
                || !identifierMeetsSdkVersionRequirement(info.getPhysicallyTunedTo(), uid)) {
            return false;
        }
        if (info.getRelatedContent() == null) {
            return true;
        }
        Iterator<ProgramSelector.Identifier> relatedContentIt = info.getRelatedContent().iterator();
        while (relatedContentIt.hasNext()) {
            if (!identifierMeetsSdkVersionRequirement(relatedContentIt.next(), uid)) {
                return false;
            }
        }
        return true;
    }

    static ProgramList.Chunk convertChunkToTargetSdkVersion(ProgramList.Chunk chunk, int uid) {
        Set<RadioManager.ProgramInfo> modified = new ArraySet<>();
        Iterator<RadioManager.ProgramInfo> modifiedIterator = chunk.getModified().iterator();
        while (modifiedIterator.hasNext()) {
            RadioManager.ProgramInfo info = modifiedIterator.next();
            if (programInfoMeetsSdkVersionRequirement(info, uid)) {
                modified.add(info);
            }
        }
        Set<UniqueProgramIdentifier> removed = new ArraySet<>();
        Iterator<UniqueProgramIdentifier> removedIterator = chunk.getRemoved().iterator();
        while (removedIterator.hasNext()) {
            UniqueProgramIdentifier id = removedIterator.next();
            if (identifierMeetsSdkVersionRequirement(id.getPrimaryId(), uid)) {
                removed.add(id);
            }
        }
        return new ProgramList.Chunk(chunk.isPurge(), chunk.isComplete(), modified, removed);
    }

    static boolean configFlagMeetsSdkVersionRequirement(int configFlag, int uid) {
        if (!Flags.hdRadioImproved() || !isAtLeastV(uid)) {
            return configFlag != ConfigFlag.FORCE_ANALOG_AM
                    && configFlag != ConfigFlag.FORCE_ANALOG_FM;
        }
        return true;
    }

    public static android.hardware.radio.Announcement announcementFromHalAnnouncement(
            Announcement hwAnnouncement) {
        return new android.hardware.radio.Announcement(
                Objects.requireNonNull(programSelectorFromHalProgramSelector(
                        hwAnnouncement.selector), "Program selector can not be null"),
                hwAnnouncement.type,
                vendorInfoFromHalVendorKeyValues(hwAnnouncement.vendorInfo)
        );
    }
}
