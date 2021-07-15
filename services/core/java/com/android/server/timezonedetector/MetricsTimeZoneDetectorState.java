/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.timezonedetector;

import static libcore.io.IoUtils.closeQuietly;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.util.proto.ProtoOutputStream;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A class that provides time zone detector state information for metrics.
 *
 * <p>
 * Regarding time zone ID ordinals:
 * <p>
 * We don't want to leak user location information by reporting time zone IDs. Instead, time zone
 * IDs are consistently identified within a given instance of this class by a numeric ID. This
 * allows comparison of IDs without revealing what those IDs are.
 */
public final class MetricsTimeZoneDetectorState {

    @IntDef(prefix = "DETECTION_MODE_",
            value = { DETECTION_MODE_MANUAL, DETECTION_MODE_GEO, DETECTION_MODE_TELEPHONY})
    @interface DetectionMode {};

    @DetectionMode
    public static final int DETECTION_MODE_MANUAL = 0;
    @DetectionMode
    public static final int DETECTION_MODE_GEO = 1;
    @DetectionMode
    public static final int DETECTION_MODE_TELEPHONY = 2;

    @NonNull
    private final ConfigurationInternal mConfigurationInternal;
    @NonNull
    private final int mDeviceTimeZoneIdOrdinal;
    @Nullable
    private final MetricsTimeZoneSuggestion mLatestManualSuggestion;
    @Nullable
    private final MetricsTimeZoneSuggestion mLatestTelephonySuggestion;
    @Nullable
    private final MetricsTimeZoneSuggestion mLatestGeolocationSuggestion;

    private MetricsTimeZoneDetectorState(
            @NonNull ConfigurationInternal configurationInternal,
            int deviceTimeZoneIdOrdinal,
            @Nullable MetricsTimeZoneSuggestion latestManualSuggestion,
            @Nullable MetricsTimeZoneSuggestion latestTelephonySuggestion,
            @Nullable MetricsTimeZoneSuggestion latestGeolocationSuggestion) {
        mConfigurationInternal = Objects.requireNonNull(configurationInternal);
        mDeviceTimeZoneIdOrdinal = deviceTimeZoneIdOrdinal;
        mLatestManualSuggestion = latestManualSuggestion;
        mLatestTelephonySuggestion = latestTelephonySuggestion;
        mLatestGeolocationSuggestion = latestGeolocationSuggestion;
    }

    /**
     * Creates {@link MetricsTimeZoneDetectorState} from the supplied parameters, using the {@link
     * OrdinalGenerator} to generate time zone ID ordinals.
     */
    public static MetricsTimeZoneDetectorState create(
            @NonNull OrdinalGenerator<String> tzIdOrdinalGenerator,
            @NonNull ConfigurationInternal configurationInternal,
            @NonNull String deviceTimeZoneId,
            @Nullable ManualTimeZoneSuggestion latestManualSuggestion,
            @Nullable TelephonyTimeZoneSuggestion latestTelephonySuggestion,
            @Nullable GeolocationTimeZoneSuggestion latestGeolocationSuggestion) {

        int deviceTimeZoneIdOrdinal =
                tzIdOrdinalGenerator.ordinal(Objects.requireNonNull(deviceTimeZoneId));
        MetricsTimeZoneSuggestion latestObfuscatedManualSuggestion =
                createMetricsTimeZoneSuggestion(tzIdOrdinalGenerator, latestManualSuggestion);
        MetricsTimeZoneSuggestion latestObfuscatedTelephonySuggestion =
                createMetricsTimeZoneSuggestion(tzIdOrdinalGenerator, latestTelephonySuggestion);
        MetricsTimeZoneSuggestion latestObfuscatedGeolocationSuggestion =
                createMetricsTimeZoneSuggestion(tzIdOrdinalGenerator, latestGeolocationSuggestion);

        return new MetricsTimeZoneDetectorState(
                configurationInternal, deviceTimeZoneIdOrdinal, latestObfuscatedManualSuggestion,
                latestObfuscatedTelephonySuggestion, latestObfuscatedGeolocationSuggestion);
    }

    /** Returns true if the device supports telephony time zone detection. */
    public boolean isTelephonyDetectionSupported() {
        return mConfigurationInternal.isTelephonyDetectionSupported();
    }

    /** Returns true if the device supports geolocation time zone detection. */
    public boolean isGeoDetectionSupported() {
        return mConfigurationInternal.isGeoDetectionSupported();
    }

    /** Returns true if user's location can be used generally. */
    public boolean isUserLocationEnabled() {
        return mConfigurationInternal.isLocationEnabled();
    }

    /** Returns the value of the geolocation time zone detection enabled setting. */
    public boolean getGeoDetectionEnabledSetting() {
        return mConfigurationInternal.getGeoDetectionEnabledSetting();
    }

    /** Returns the value of the auto time zone detection enabled setting. */
    public boolean getAutoDetectionEnabledSetting() {
        return mConfigurationInternal.getAutoDetectionEnabledSetting();
    }

    /**
     * Returns the detection mode the device is currently using, which can be influenced by various
     * things besides the user's setting.
     */
    @DetectionMode
    public int getDetectionMode() {
        if (!mConfigurationInternal.getAutoDetectionEnabledBehavior()) {
            return DETECTION_MODE_MANUAL;
        } else if (mConfigurationInternal.getGeoDetectionEnabledBehavior()) {
            return DETECTION_MODE_GEO;
        } else {
            return DETECTION_MODE_TELEPHONY;
        }
    }

    /**
     * Returns the ordinal for the device's currently set time zone ID.
     * See {@link MetricsTimeZoneDetectorState} for information about ordinals.
     */
    @NonNull
    public int getDeviceTimeZoneIdOrdinal() {
        return mDeviceTimeZoneIdOrdinal;
    }

    /**
     * Returns bytes[] for a {@link MetricsTimeZoneSuggestion} for the last manual
     * suggestion received.
     */
    @Nullable
    public byte[] getLatestManualSuggestionProtoBytes() {
        return suggestionProtoBytes(mLatestManualSuggestion);
    }

    /**
     * Returns bytes[] for a {@link MetricsTimeZoneSuggestion} for the last, best
     * telephony suggestion received.
     */
    @Nullable
    public byte[] getLatestTelephonySuggestionProtoBytes() {
        return suggestionProtoBytes(mLatestTelephonySuggestion);
    }

    /**
     * Returns bytes[] for a {@link MetricsTimeZoneSuggestion} for the last geolocation
     * suggestion received.
     */
    @Nullable
    public byte[] getLatestGeolocationSuggestionProtoBytes() {
        return suggestionProtoBytes(mLatestGeolocationSuggestion);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MetricsTimeZoneDetectorState that = (MetricsTimeZoneDetectorState) o;
        return mDeviceTimeZoneIdOrdinal == that.mDeviceTimeZoneIdOrdinal
                && mConfigurationInternal.equals(that.mConfigurationInternal)
                && Objects.equals(mLatestManualSuggestion, that.mLatestManualSuggestion)
                && Objects.equals(mLatestTelephonySuggestion, that.mLatestTelephonySuggestion)
                && Objects.equals(mLatestGeolocationSuggestion, that.mLatestGeolocationSuggestion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mConfigurationInternal, mDeviceTimeZoneIdOrdinal,
                mLatestManualSuggestion, mLatestTelephonySuggestion, mLatestGeolocationSuggestion);
    }

    @Override
    public String toString() {
        return "MetricsTimeZoneDetectorState{"
                + "mConfigurationInternal=" + mConfigurationInternal
                + ", mDeviceTimeZoneIdOrdinal=" + mDeviceTimeZoneIdOrdinal
                + ", mLatestManualSuggestion=" + mLatestManualSuggestion
                + ", mLatestTelephonySuggestion=" + mLatestTelephonySuggestion
                + ", mLatestGeolocationSuggestion=" + mLatestGeolocationSuggestion
                + '}';
    }

    private static byte[] suggestionProtoBytes(
            @Nullable MetricsTimeZoneSuggestion suggestion) {
        if (suggestion == null) {
            return null;
        }
        return suggestion.toBytes();
    }

    @Nullable
    private static MetricsTimeZoneSuggestion createMetricsTimeZoneSuggestion(
            @NonNull OrdinalGenerator<String> zoneIdOrdinalGenerator,
            @NonNull ManualTimeZoneSuggestion manualSuggestion) {
        if (manualSuggestion == null) {
            return null;
        }

        int zoneIdOrdinal = zoneIdOrdinalGenerator.ordinal(manualSuggestion.getZoneId());
        return MetricsTimeZoneSuggestion.createCertain(
                new int[] { zoneIdOrdinal });
    }

    @Nullable
    private static MetricsTimeZoneSuggestion createMetricsTimeZoneSuggestion(
            @NonNull OrdinalGenerator<String> zoneIdOrdinalGenerator,
            @NonNull TelephonyTimeZoneSuggestion telephonySuggestion) {
        if (telephonySuggestion == null) {
            return null;
        }
        if (telephonySuggestion.getZoneId() == null) {
            return MetricsTimeZoneSuggestion.createUncertain();
        }
        int zoneIdOrdinal = zoneIdOrdinalGenerator.ordinal(telephonySuggestion.getZoneId());
        return MetricsTimeZoneSuggestion.createCertain(new int[] { zoneIdOrdinal });
    }

    @Nullable
    private static MetricsTimeZoneSuggestion createMetricsTimeZoneSuggestion(
            @NonNull OrdinalGenerator<String> zoneIdOrdinalGenerator,
            @Nullable GeolocationTimeZoneSuggestion geolocationSuggestion) {
        if (geolocationSuggestion == null) {
            return null;
        }

        List<String> zoneIds = geolocationSuggestion.getZoneIds();
        if (zoneIds == null) {
            return MetricsTimeZoneSuggestion.createUncertain();
        }
        return MetricsTimeZoneSuggestion.createCertain(zoneIdOrdinalGenerator.ordinals(zoneIds));
    }

    /**
     * A Java class that closely matches the android.app.time.MetricsTimeZoneSuggestion
     * proto definition.
     */
    private static final class MetricsTimeZoneSuggestion {
        @Nullable
        private final int[] mZoneIdOrdinals;

        MetricsTimeZoneSuggestion(@Nullable int[] zoneIdOrdinals) {
            mZoneIdOrdinals = zoneIdOrdinals;
        }

        @NonNull
        static MetricsTimeZoneSuggestion createUncertain() {
            return new MetricsTimeZoneSuggestion(null);
        }

        public static MetricsTimeZoneSuggestion createCertain(
                @NonNull int[] zoneIdOrdinals) {
            return new MetricsTimeZoneSuggestion(zoneIdOrdinals);
        }

        boolean isCertain() {
            return mZoneIdOrdinals != null;
        }

        @Nullable
        int[] getZoneIdOrdinals() {
            return mZoneIdOrdinals;
        }

        byte[] toBytes() {
            // We don't get access to the atoms.proto definition for nested proto fields, so we use
            // an identically specified proto.
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ProtoOutputStream protoOutputStream = new ProtoOutputStream(byteArrayOutputStream);
            int typeProtoValue = isCertain()
                    ? android.app.time.MetricsTimeZoneSuggestion.CERTAIN
                    : android.app.time.MetricsTimeZoneSuggestion.UNCERTAIN;
            protoOutputStream.write(android.app.time.MetricsTimeZoneSuggestion.TYPE,
                    typeProtoValue);
            if (isCertain()) {
                for (int zoneIdOrdinal : getZoneIdOrdinals()) {
                    protoOutputStream.write(
                            android.app.time.MetricsTimeZoneSuggestion.TIME_ZONE_ORDINALS,
                            zoneIdOrdinal);
                }
            }
            protoOutputStream.flush();
            closeQuietly(byteArrayOutputStream);
            return byteArrayOutputStream.toByteArray();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            MetricsTimeZoneSuggestion that = (MetricsTimeZoneSuggestion) o;
            return Arrays.equals(mZoneIdOrdinals, that.mZoneIdOrdinals);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(mZoneIdOrdinals);
        }

        @Override
        public String toString() {
            return "MetricsTimeZoneSuggestion{"
                    + "mZoneIdOrdinals=" + Arrays.toString(mZoneIdOrdinals)
                    + '}';
        }
    }
}
