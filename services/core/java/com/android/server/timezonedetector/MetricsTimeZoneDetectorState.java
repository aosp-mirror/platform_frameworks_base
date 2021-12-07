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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A class that provides time zone detector state information for metrics.
 *
 * <p>
 * Regarding the use of time zone ID ordinals in metrics / telemetry:
 * <p>
 * For general metrics, we don't want to leak user location information by reporting time zone
 * IDs. Instead, time zone IDs are consistently identified within a given instance of this class by
 * a numeric ID (ordinal). This allows comparison of IDs without revealing what those IDs are.
 * See {@link #isEnhancedMetricsCollectionEnabled()} for the setting that enables actual IDs to be
 * collected.
 */
public final class MetricsTimeZoneDetectorState {

    @IntDef(prefix = "DETECTION_MODE_",
            value = { DETECTION_MODE_UNKNOWN, DETECTION_MODE_MANUAL, DETECTION_MODE_GEO,
                    DETECTION_MODE_TELEPHONY }
    )
    @Retention(RetentionPolicy.SOURCE)
    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    public @interface DetectionMode {};

    public static final @DetectionMode int DETECTION_MODE_UNKNOWN = 0;
    public static final @DetectionMode int DETECTION_MODE_MANUAL = 1;
    public static final @DetectionMode int DETECTION_MODE_GEO = 2;
    public static final @DetectionMode int DETECTION_MODE_TELEPHONY = 3;

    @NonNull private final ConfigurationInternal mConfigurationInternal;
    private final int mDeviceTimeZoneIdOrdinal;
    @Nullable private final String mDeviceTimeZoneId;
    @Nullable private final MetricsTimeZoneSuggestion mLatestManualSuggestion;
    @Nullable private final MetricsTimeZoneSuggestion mLatestTelephonySuggestion;
    @Nullable private final MetricsTimeZoneSuggestion mLatestGeolocationSuggestion;

    private MetricsTimeZoneDetectorState(
            @NonNull ConfigurationInternal configurationInternal,
            int deviceTimeZoneIdOrdinal,
            @Nullable String deviceTimeZoneId,
            @Nullable MetricsTimeZoneSuggestion latestManualSuggestion,
            @Nullable MetricsTimeZoneSuggestion latestTelephonySuggestion,
            @Nullable MetricsTimeZoneSuggestion latestGeolocationSuggestion) {
        mConfigurationInternal = Objects.requireNonNull(configurationInternal);
        mDeviceTimeZoneIdOrdinal = deviceTimeZoneIdOrdinal;
        mDeviceTimeZoneId = deviceTimeZoneId;
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

        boolean includeZoneIds = configurationInternal.isEnhancedMetricsCollectionEnabled();
        String metricDeviceTimeZoneId = includeZoneIds ? deviceTimeZoneId : null;
        int deviceTimeZoneIdOrdinal =
                tzIdOrdinalGenerator.ordinal(Objects.requireNonNull(deviceTimeZoneId));
        MetricsTimeZoneSuggestion latestCanonicalManualSuggestion =
                createMetricsTimeZoneSuggestion(
                        tzIdOrdinalGenerator, latestManualSuggestion, includeZoneIds);
        MetricsTimeZoneSuggestion latestCanonicalTelephonySuggestion =
                createMetricsTimeZoneSuggestion(
                        tzIdOrdinalGenerator, latestTelephonySuggestion, includeZoneIds);
        MetricsTimeZoneSuggestion latestCanonicalGeolocationSuggestion =
                createMetricsTimeZoneSuggestion(
                        tzIdOrdinalGenerator, latestGeolocationSuggestion, includeZoneIds);

        return new MetricsTimeZoneDetectorState(
                configurationInternal, deviceTimeZoneIdOrdinal, metricDeviceTimeZoneId,
                latestCanonicalManualSuggestion, latestCanonicalTelephonySuggestion,
                latestCanonicalGeolocationSuggestion);
    }

    /** Returns true if the device supports telephony time zone detection. */
    public boolean isTelephonyDetectionSupported() {
        return mConfigurationInternal.isTelephonyDetectionSupported();
    }

    /** Returns true if the device supports geolocation time zone detection. */
    public boolean isGeoDetectionSupported() {
        return mConfigurationInternal.isGeoDetectionSupported();
    }

    /** Returns true if the device supports telephony time zone detection fallback. */
    public boolean isTelephonyTimeZoneFallbackSupported() {
        return mConfigurationInternal.isTelephonyFallbackSupported();
    }

    /**
     * Returns {@code true} if location time zone detection should run all the time on supported
     * devices, even when the user has not enabled it explicitly in settings. Enabled for internal
     * testing only.
     */
    public boolean getGeoDetectionRunInBackgroundEnabled() {
        return mConfigurationInternal.getGeoDetectionRunInBackgroundEnabled();
    }

    /** Returns true if enhanced metric collection is enabled. */
    public boolean isEnhancedMetricsCollectionEnabled() {
        return mConfigurationInternal.isEnhancedMetricsCollectionEnabled();
    }

    /** Returns true if user's location can be used generally. */
    public boolean getUserLocationEnabledSetting() {
        return mConfigurationInternal.getLocationEnabledSetting();
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
    public @DetectionMode int getDetectionMode() {
        switch (mConfigurationInternal.getDetectionMode()) {
            case ConfigurationInternal.DETECTION_MODE_MANUAL:
                return DETECTION_MODE_MANUAL;
            case ConfigurationInternal.DETECTION_MODE_GEO:
                return DETECTION_MODE_GEO;
            case ConfigurationInternal.DETECTION_MODE_TELEPHONY:
                return DETECTION_MODE_TELEPHONY;
            default:
                return DETECTION_MODE_UNKNOWN;
        }
    }

    /**
     * Returns the ordinal for the device's current time zone ID.
     * See {@link MetricsTimeZoneDetectorState} for information about ordinals.
     */
    public int getDeviceTimeZoneIdOrdinal() {
        return mDeviceTimeZoneIdOrdinal;
    }

    /**
     * Returns the device's current time zone ID. This will only be populated if {@link
     * #isEnhancedMetricsCollectionEnabled()} is {@code true}. See {@link
     * MetricsTimeZoneDetectorState} for details.
     */
    @Nullable
    public String getDeviceTimeZoneId() {
        return mDeviceTimeZoneId;
    }

    /**
     * Returns a canonical form of the last manual suggestion received.
     */
    @Nullable
    public MetricsTimeZoneSuggestion getLatestManualSuggestion() {
        return mLatestManualSuggestion;
    }

    /**
     * Returns a canonical form of the last telephony suggestion received.
     */
    @Nullable
    public MetricsTimeZoneSuggestion getLatestTelephonySuggestion() {
        return mLatestTelephonySuggestion;
    }

    /**
     * Returns a canonical form of last geolocation suggestion received.
     */
    @Nullable
    public MetricsTimeZoneSuggestion getLatestGeolocationSuggestion() {
        return mLatestGeolocationSuggestion;
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
                && Objects.equals(mDeviceTimeZoneId, that.mDeviceTimeZoneId)
                && mConfigurationInternal.equals(that.mConfigurationInternal)
                && Objects.equals(mLatestManualSuggestion, that.mLatestManualSuggestion)
                && Objects.equals(mLatestTelephonySuggestion, that.mLatestTelephonySuggestion)
                && Objects.equals(mLatestGeolocationSuggestion, that.mLatestGeolocationSuggestion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mConfigurationInternal, mDeviceTimeZoneIdOrdinal, mDeviceTimeZoneId,
                mLatestManualSuggestion, mLatestTelephonySuggestion, mLatestGeolocationSuggestion);
    }

    @Override
    public String toString() {
        return "MetricsTimeZoneDetectorState{"
                + "mConfigurationInternal=" + mConfigurationInternal
                + ", mDeviceTimeZoneIdOrdinal=" + mDeviceTimeZoneIdOrdinal
                + ", mDeviceTimeZoneId=" + mDeviceTimeZoneId
                + ", mLatestManualSuggestion=" + mLatestManualSuggestion
                + ", mLatestTelephonySuggestion=" + mLatestTelephonySuggestion
                + ", mLatestGeolocationSuggestion=" + mLatestGeolocationSuggestion
                + '}';
    }

    @Nullable
    private static MetricsTimeZoneSuggestion createMetricsTimeZoneSuggestion(
            @NonNull OrdinalGenerator<String> zoneIdOrdinalGenerator,
            @NonNull ManualTimeZoneSuggestion manualSuggestion,
            boolean includeFullZoneIds) {
        if (manualSuggestion == null) {
            return null;
        }

        String suggestionZoneId = manualSuggestion.getZoneId();
        String[] metricZoneIds = includeFullZoneIds ? new String[] { suggestionZoneId } : null;
        int[] zoneIdOrdinals = new int[] { zoneIdOrdinalGenerator.ordinal(suggestionZoneId) };
        return MetricsTimeZoneSuggestion.createCertain(metricZoneIds, zoneIdOrdinals);
    }

    @Nullable
    private static MetricsTimeZoneSuggestion createMetricsTimeZoneSuggestion(
            @NonNull OrdinalGenerator<String> zoneIdOrdinalGenerator,
            @NonNull TelephonyTimeZoneSuggestion telephonySuggestion,
            boolean includeFullZoneIds) {
        if (telephonySuggestion == null) {
            return null;
        }
        String suggestionZoneId = telephonySuggestion.getZoneId();
        if (suggestionZoneId == null) {
            return MetricsTimeZoneSuggestion.createUncertain();
        }
        String[] metricZoneIds = includeFullZoneIds ? new String[] { suggestionZoneId } : null;
        int[] zoneIdOrdinals = new int[] { zoneIdOrdinalGenerator.ordinal(suggestionZoneId) };
        return MetricsTimeZoneSuggestion.createCertain(metricZoneIds, zoneIdOrdinals);
    }

    @Nullable
    private static MetricsTimeZoneSuggestion createMetricsTimeZoneSuggestion(
            @NonNull OrdinalGenerator<String> zoneIdOrdinalGenerator,
            @Nullable GeolocationTimeZoneSuggestion geolocationSuggestion,
            boolean includeFullZoneIds) {
        if (geolocationSuggestion == null) {
            return null;
        }

        List<String> zoneIds = geolocationSuggestion.getZoneIds();
        if (zoneIds == null) {
            return MetricsTimeZoneSuggestion.createUncertain();
        }
        String[] metricZoneIds = includeFullZoneIds ? zoneIds.toArray(new String[0]) : null;
        int[] zoneIdOrdinals = zoneIdOrdinalGenerator.ordinals(zoneIds);
        return MetricsTimeZoneSuggestion.createCertain(metricZoneIds, zoneIdOrdinals);
    }

    /**
     * A Java class that represents a generic time zone suggestion, i.e. one that is independent of
     * origin-specific information. This closely matches the metrics atoms.proto
     * MetricsTimeZoneSuggestion proto definition.
     */
    public static final class MetricsTimeZoneSuggestion {
        @Nullable private final String[] mZoneIds;
        @Nullable private final int[] mZoneIdOrdinals;

        private MetricsTimeZoneSuggestion(
                @Nullable String[] zoneIds, @Nullable int[] zoneIdOrdinals) {
            mZoneIds = zoneIds;
            mZoneIdOrdinals = zoneIdOrdinals;
        }

        @NonNull
        static MetricsTimeZoneSuggestion createUncertain() {
            return new MetricsTimeZoneSuggestion(null, null);
        }

        @NonNull
        static MetricsTimeZoneSuggestion createCertain(
                @Nullable String[] zoneIds, @NonNull int[] zoneIdOrdinals) {
            return new MetricsTimeZoneSuggestion(zoneIds, zoneIdOrdinals);
        }

        public boolean isCertain() {
            return mZoneIdOrdinals != null;
        }

        /**
         * Returns ordinals for the time zone IDs contained in the suggestion.
         * See {@link MetricsTimeZoneDetectorState} for information about ordinals.
         */
        @Nullable
        public int[] getZoneIdOrdinals() {
            return mZoneIdOrdinals;
        }

        /**
         * Returns the time zone IDs contained in the suggestion. This will only be populated if
         * {@link #isEnhancedMetricsCollectionEnabled()} is {@code true}. See {@link
         * MetricsTimeZoneDetectorState} for details.
         */
        @Nullable
        public String[] getZoneIds() {
            return mZoneIds;
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
            return Arrays.equals(mZoneIdOrdinals, that.mZoneIdOrdinals)
                    && Arrays.equals(mZoneIds, that.mZoneIds);
        }

        @Override
        public int hashCode() {
            int result = Arrays.hashCode(mZoneIds);
            result = 31 * result + Arrays.hashCode(mZoneIdOrdinals);
            return result;
        }

        @Override
        public String toString() {
            return "MetricsTimeZoneSuggestion{"
                    + "mZoneIdOrdinals=" + Arrays.toString(mZoneIdOrdinals)
                    + ", mZoneIds=" + Arrays.toString(mZoneIds)
                    + '}';
        }
    }
}
