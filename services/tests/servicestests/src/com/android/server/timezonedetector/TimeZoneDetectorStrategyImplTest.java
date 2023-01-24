/*
 * Copyright 2019 The Android Open Source Project
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

import static android.app.time.DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_RUNNING;
import static android.app.time.DetectorStatusTypes.DETECTOR_STATUS_RUNNING;
import static android.app.time.LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_IS_CERTAIN;
import static android.app.time.LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_IS_UNCERTAIN;
import static android.app.time.LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_NOT_PRESENT;
import static android.app.time.LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_NOT_READY;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_EMULATOR_ZONE_ID;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_ONLY;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_SINGLE_ZONE;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_UNKNOWN;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_UNKNOWN;

import static com.android.server.SystemTimeZone.TIME_ZONE_CONFIDENCE_HIGH;
import static com.android.server.SystemTimeZone.TIME_ZONE_CONFIDENCE_LOW;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_HIGH;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_HIGHEST;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_LOW;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_MEDIUM;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_NONE;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_USAGE_THRESHOLD;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.time.LocationTimeZoneAlgorithmStatus;
import android.app.time.TelephonyTimeZoneAlgorithmStatus;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.app.time.TimeZoneDetectorStatus;
import android.app.time.TimeZoneState;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion.MatchType;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion.Quality;
import android.service.timezone.TimeZoneProviderStatus;

import com.android.server.SystemTimeZone.TimeZoneConfidence;
import com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.QualifiedTelephonyTimeZoneSuggestion;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

/**
 * White-box unit tests for {@link TimeZoneDetectorStrategyImpl}.
 */
@RunWith(JUnitParamsRunner.class)
public class TimeZoneDetectorStrategyImplTest {

    private static final @UserIdInt int USER_ID = 9876;
    private static final long ARBITRARY_ELAPSED_REALTIME_MILLIS = 1234;
    /** A time zone used for initialization that does not occur elsewhere in tests. */
    private static final String ARBITRARY_TIME_ZONE_ID = "Etc/UTC";
    private static final int SLOT_INDEX1 = 10000;
    private static final int SLOT_INDEX2 = 20000;

    // Telephony test cases are ordered so that each successive one is of the same or higher score
    // than the previous.
    private static final TelephonyTestCase[] TELEPHONY_TEST_CASES = new TelephonyTestCase[] {
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                    QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS, TELEPHONY_SCORE_LOW),
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                    QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET, TELEPHONY_SCORE_MEDIUM),
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET,
                    QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET, TELEPHONY_SCORE_MEDIUM),
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY, QUALITY_SINGLE_ZONE,
                    TELEPHONY_SCORE_HIGH),
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                    TELEPHONY_SCORE_HIGH),
            newTelephonyTestCase(MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY,
                    QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET, TELEPHONY_SCORE_HIGHEST),
            newTelephonyTestCase(MATCH_TYPE_EMULATOR_ZONE_ID, QUALITY_SINGLE_ZONE,
                    TELEPHONY_SCORE_HIGHEST),
    };

    private static final ConfigurationInternal CONFIG_USER_RESTRICTED_AUTO_DISABLED =
            new ConfigurationInternal.Builder()
                    .setUserId(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(false)
                    .setAutoDetectionEnabledSetting(false)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(false)
                    .build();

    private static final ConfigurationInternal CONFIG_USER_RESTRICTED_AUTO_ENABLED =
            new ConfigurationInternal.Builder()
                    .setUserId(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(false)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(true)
                    .build();

    private static final ConfigurationInternal CONFIG_AUTO_DETECT_NOT_SUPPORTED =
            new ConfigurationInternal.Builder()
                    .setUserId(USER_ID)
                    .setTelephonyDetectionFeatureSupported(false)
                    .setGeoDetectionFeatureSupported(false)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionEnabledSetting(false)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(false)
                    .build();

    private static final ConfigurationInternal CONFIG_AUTO_DISABLED_GEO_DISABLED =
            new ConfigurationInternal.Builder()
                    .setUserId(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionEnabledSetting(false)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(false)
                    .build();

    private static final ConfigurationInternal CONFIG_AUTO_ENABLED_GEO_DISABLED =
            new ConfigurationInternal.Builder()
                    .setUserId(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(false)
                    .build();

    private static final ConfigurationInternal CONFIG_AUTO_ENABLED_GEO_ENABLED =
            new ConfigurationInternal.Builder()
                    .setUserId(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(true)
                    .build();

    private static final TelephonyTimeZoneAlgorithmStatus TELEPHONY_ALGORITHM_RUNNING_STATUS =
            new TelephonyTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING);

    private FakeServiceConfigAccessor mFakeServiceConfigAccessorSpy;
    private FakeEnvironment mFakeEnvironment;

    private TimeZoneDetectorStrategyImpl mTimeZoneDetectorStrategy;

    @Before
    public void setUp() {
        mFakeEnvironment = new FakeEnvironment();
        mFakeServiceConfigAccessorSpy = spy(new FakeServiceConfigAccessor());
        mFakeServiceConfigAccessorSpy.initializeCurrentUserConfiguration(
                CONFIG_AUTO_DISABLED_GEO_DISABLED);

        mTimeZoneDetectorStrategy = new TimeZoneDetectorStrategyImpl(
                mFakeServiceConfigAccessorSpy, mFakeEnvironment);
    }

    @Test
    public void testChangeListenerBehavior_currentUser() throws Exception {
        ConfigurationInternal currentUserConfig = CONFIG_AUTO_DISABLED_GEO_DISABLED;
        // The strategy initializes itself with the current user's config during construction.
        assertEquals(currentUserConfig,
                mTimeZoneDetectorStrategy.getCachedCapabilitiesAndConfigForTests());

        TestStateChangeListener stateChangeListener = new TestStateChangeListener();
        mTimeZoneDetectorStrategy.addChangeListener(stateChangeListener);

        boolean bypassUserPolicyChecks = false;

        // Report a config change, but not one that actually changes anything.
        {
            mFakeServiceConfigAccessorSpy.simulateCurrentUserConfigurationInternalChange(
                    CONFIG_AUTO_DISABLED_GEO_DISABLED);
            assertStateChangeNotificationsSent(stateChangeListener, 0);
            assertEquals(CONFIG_AUTO_DISABLED_GEO_DISABLED,
                    mTimeZoneDetectorStrategy.getCachedCapabilitiesAndConfigForTests());
        }

        // Report a config change that actually changes something.
        {
            mFakeServiceConfigAccessorSpy.simulateCurrentUserConfigurationInternalChange(
                    CONFIG_AUTO_ENABLED_GEO_ENABLED);
            assertStateChangeNotificationsSent(stateChangeListener, 1);
            assertEquals(CONFIG_AUTO_ENABLED_GEO_ENABLED,
                    mTimeZoneDetectorStrategy.getCachedCapabilitiesAndConfigForTests());
        }

        // Perform a (current user) update via the strategy.
        {
            TimeZoneConfiguration requestedChanges =
                    new TimeZoneConfiguration.Builder().setGeoDetectionEnabled(false).build();
            mTimeZoneDetectorStrategy.updateConfiguration(
                    USER_ID, requestedChanges, bypassUserPolicyChecks);
            assertStateChangeNotificationsSent(stateChangeListener, 1);
        }
    }

    // Perform a (not current user) update via the strategy. There's no listener behavior for
    // updates to "other" users.
    @Test
    public void testChangeListenerBehavior_otherUser() throws Exception {
        ConfigurationInternal currentUserConfig = CONFIG_AUTO_DISABLED_GEO_DISABLED;
        // The strategy initializes itself with the current user's config during construction.
        assertEquals(currentUserConfig,
                mTimeZoneDetectorStrategy.getCachedCapabilitiesAndConfigForTests());

        TestStateChangeListener stateChangeListener = new TestStateChangeListener();
        mTimeZoneDetectorStrategy.addChangeListener(stateChangeListener);

        boolean bypassUserPolicyChecks = false;

        int otherUserId = currentUserConfig.getUserId() + 1;
        ConfigurationInternal otherUserConfig = new ConfigurationInternal.Builder(currentUserConfig)
                .setUserId(otherUserId)
                .setGeoDetectionEnabledSetting(true)
                .build();
        mFakeServiceConfigAccessorSpy.initializeOtherUserConfiguration(otherUserConfig);

        TimeZoneConfiguration requestedChanges =
                new TimeZoneConfiguration.Builder().setGeoDetectionEnabled(false).build();
        mTimeZoneDetectorStrategy.updateConfiguration(
                otherUserId, requestedChanges, bypassUserPolicyChecks);

        // Only changes to the current user's config are notified.
        assertStateChangeNotificationsSent(stateChangeListener, 0);
    }

    // Current user behavior: the strategy caches and returns the latest configuration.
    @Test
    public void testReadAndWriteConfiguration_currentUser() throws Exception {
        ConfigurationInternal currentUserConfig = CONFIG_AUTO_ENABLED_GEO_DISABLED;
        mFakeServiceConfigAccessorSpy.simulateCurrentUserConfigurationInternalChange(
                currentUserConfig);

        int otherUserId = currentUserConfig.getUserId() + 1;
        ConfigurationInternal otherUserConfig = new ConfigurationInternal.Builder(currentUserConfig)
                .setUserId(otherUserId)
                .setGeoDetectionEnabledSetting(true)
                .build();
        mFakeServiceConfigAccessorSpy.simulateOtherUserConfigurationInternalChange(otherUserConfig);
        reset(mFakeServiceConfigAccessorSpy);

        final boolean bypassUserPolicyChecks = false;

        ConfigurationInternal cachedConfigurationInternal =
                mTimeZoneDetectorStrategy.getCachedCapabilitiesAndConfigForTests();
        assertEquals(currentUserConfig, cachedConfigurationInternal);

        // Confirm getCapabilitiesAndConfig() does not call through to the ServiceConfigAccessor.
        {
            reset(mFakeServiceConfigAccessorSpy);
            TimeZoneCapabilitiesAndConfig actualCapabilitiesAndConfig =
                    mTimeZoneDetectorStrategy.getCapabilitiesAndConfig(
                            currentUserConfig.getUserId(), bypassUserPolicyChecks);
            verify(mFakeServiceConfigAccessorSpy, never()).getConfigurationInternal(
                    currentUserConfig.getUserId());

            assertEquals(currentUserConfig.asCapabilities(bypassUserPolicyChecks),
                    actualCapabilitiesAndConfig.getCapabilities());
            assertEquals(currentUserConfig.asConfiguration(),
                    actualCapabilitiesAndConfig.getConfiguration());
        }

        // Confirm updateConfiguration() calls through to the ServiceConfigAccessor and updates
        // the cached copy.
        {
            boolean newGeoDetectionEnabled =
                    !cachedConfigurationInternal.asConfiguration().isGeoDetectionEnabled();
            TimeZoneConfiguration requestedChanges = new TimeZoneConfiguration.Builder()
                    .setGeoDetectionEnabled(newGeoDetectionEnabled)
                    .build();
            ConfigurationInternal expectedConfigAfterChange =
                    new ConfigurationInternal.Builder(cachedConfigurationInternal)
                            .setGeoDetectionEnabledSetting(newGeoDetectionEnabled)
                            .build();

            reset(mFakeServiceConfigAccessorSpy);
            mTimeZoneDetectorStrategy.updateConfiguration(
                    currentUserConfig.getUserId(), requestedChanges, bypassUserPolicyChecks);
            verify(mFakeServiceConfigAccessorSpy, times(1)).updateConfiguration(
                    currentUserConfig.getUserId(), requestedChanges, bypassUserPolicyChecks);
            assertEquals(expectedConfigAfterChange,
                    mTimeZoneDetectorStrategy.getCachedCapabilitiesAndConfigForTests());
        }
    }

    // Not current user behavior: the strategy reads from the ServiceConfigAccessor.
    @Test
    public void testReadAndWriteConfiguration_otherUser() throws Exception {
        ConfigurationInternal currentUserConfig = CONFIG_AUTO_ENABLED_GEO_DISABLED;
        mFakeServiceConfigAccessorSpy.simulateCurrentUserConfigurationInternalChange(
                currentUserConfig);

        int otherUserId = currentUserConfig.getUserId() + 1;
        ConfigurationInternal otherUserConfig = new ConfigurationInternal.Builder(currentUserConfig)
                .setUserId(otherUserId)
                .setGeoDetectionEnabledSetting(true)
                .build();
        mFakeServiceConfigAccessorSpy.simulateOtherUserConfigurationInternalChange(otherUserConfig);
        reset(mFakeServiceConfigAccessorSpy);

        final boolean bypassUserPolicyChecks = false;

        // Confirm getCapabilitiesAndConfig() does not call through to the ServiceConfigAccessor.
        {
            reset(mFakeServiceConfigAccessorSpy);
            TimeZoneCapabilitiesAndConfig actualCapabilitiesAndConfig =
                    mTimeZoneDetectorStrategy.getCapabilitiesAndConfig(
                            otherUserId, bypassUserPolicyChecks);
            verify(mFakeServiceConfigAccessorSpy, times(1)).getConfigurationInternal(otherUserId);

            assertEquals(otherUserConfig.asCapabilities(bypassUserPolicyChecks),
                    actualCapabilitiesAndConfig.getCapabilities());
            assertEquals(otherUserConfig.asConfiguration(),
                    actualCapabilitiesAndConfig.getConfiguration());
        }

        // Confirm updateConfiguration() calls through to the ServiceConfigAccessor and doesn't
        // touch the cached copy.
        {
            ConfigurationInternal cachedConfigBeforeChange =
                    mTimeZoneDetectorStrategy.getCachedCapabilitiesAndConfigForTests();
            boolean newGeoDetectionEnabled =
                    !otherUserConfig.asConfiguration().isGeoDetectionEnabled();
            TimeZoneConfiguration requestedChanges = new TimeZoneConfiguration.Builder()
                    .setGeoDetectionEnabled(newGeoDetectionEnabled)
                    .build();

            reset(mFakeServiceConfigAccessorSpy);
            mTimeZoneDetectorStrategy.updateConfiguration(
                    currentUserConfig.getUserId(), requestedChanges, bypassUserPolicyChecks);
            verify(mFakeServiceConfigAccessorSpy, times(1)).updateConfiguration(
                    currentUserConfig.getUserId(), requestedChanges, bypassUserPolicyChecks);
            assertEquals(cachedConfigBeforeChange,
                    mTimeZoneDetectorStrategy.getCachedCapabilitiesAndConfigForTests());
        }
    }

    @Test
    public void testEmptyTelephonySuggestions() {
        TelephonyTimeZoneSuggestion slotIndex1TimeZoneSuggestion =
                createEmptySlotIndex1Suggestion();
        TelephonyTimeZoneSuggestion slotIndex2TimeZoneSuggestion =
                createEmptySlotIndex2Suggestion();
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_DISABLED)
                .resetConfigurationTracking();

        script.simulateTelephonyTimeZoneSuggestion(slotIndex1TimeZoneSuggestion)
                .verifyTimeZoneNotChanged();

        // Assert internal service state.
        QualifiedTelephonyTimeZoneSuggestion expectedSlotIndex1ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(slotIndex1TimeZoneSuggestion,
                        TELEPHONY_SCORE_NONE);
        script.verifyLatestQualifiedTelephonySuggestionReceived(
                SLOT_INDEX1, expectedSlotIndex1ScoredSuggestion)
                .verifyLatestQualifiedTelephonySuggestionReceived(SLOT_INDEX2, null);
        assertEquals(expectedSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

        script.simulateTelephonyTimeZoneSuggestion(slotIndex2TimeZoneSuggestion)
                .verifyTimeZoneNotChanged();

        // Assert internal service state.
        QualifiedTelephonyTimeZoneSuggestion expectedSlotIndex2ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(slotIndex2TimeZoneSuggestion,
                        TELEPHONY_SCORE_NONE);
        script.verifyLatestQualifiedTelephonySuggestionReceived(
                        SLOT_INDEX1, expectedSlotIndex1ScoredSuggestion)
                .verifyLatestQualifiedTelephonySuggestionReceived(
                        SLOT_INDEX2, expectedSlotIndex2ScoredSuggestion);
        // SlotIndex1 should always beat slotIndex2, all other things being equal.
        assertEquals(expectedSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
    }

    /**
     * Telephony suggestions have quality metadata. Ordinarily, low scoring suggestions are not
     * used, but this is not true if the device's time zone setting is uninitialized.
     */
    @Test
    public void testTelephonySuggestionsWhenTimeZoneUninitialized() {
        assertTrue(TELEPHONY_SCORE_LOW < TELEPHONY_SCORE_USAGE_THRESHOLD);
        assertTrue(TELEPHONY_SCORE_HIGH >= TELEPHONY_SCORE_USAGE_THRESHOLD);
        TelephonyTestCase testCase = newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS, TELEPHONY_SCORE_LOW);
        TelephonyTestCase testCase2 = newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                QUALITY_SINGLE_ZONE, TELEPHONY_SCORE_HIGH);

        Script script = new Script()
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_DISABLED)
                .resetConfigurationTracking();

        // A low quality suggestions will not be taken: The device time zone setting is left
        // uninitialized.
        {
            TelephonyTimeZoneSuggestion lowQualitySuggestion =
                    testCase.createSuggestion(SLOT_INDEX1, "America/New_York");
            script.simulateTelephonyTimeZoneSuggestion(lowQualitySuggestion)
                    .verifyTimeZoneNotChanged();

            // Assert internal service state.
            QualifiedTelephonyTimeZoneSuggestion expectedScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(
                            lowQualitySuggestion, testCase.expectedScore);
            script.verifyLatestQualifiedTelephonySuggestionReceived(
                    SLOT_INDEX1, expectedScoredSuggestion);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
        }

        // A good quality suggestion will be used.
        {
            TelephonyTimeZoneSuggestion goodQualitySuggestion =
                    testCase2.createSuggestion(SLOT_INDEX1, "Europe/London");
            script.simulateTelephonyTimeZoneSuggestion(goodQualitySuggestion)
                    .verifyTimeZoneChangedAndReset(goodQualitySuggestion);

            // Assert internal service state.
            QualifiedTelephonyTimeZoneSuggestion expectedScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(
                            goodQualitySuggestion, testCase2.expectedScore);
            script.verifyLatestQualifiedTelephonySuggestionReceived(
                    SLOT_INDEX1, expectedScoredSuggestion);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
        }

        // A low quality suggestion will be accepted, but not used to set the device time zone.
        {
            TelephonyTimeZoneSuggestion lowQualitySuggestion2 =
                    testCase.createSuggestion(SLOT_INDEX1, "America/Los_Angeles");
            script.simulateTelephonyTimeZoneSuggestion(lowQualitySuggestion2)
                    .verifyTimeZoneNotChanged();

            // Assert internal service state.
            QualifiedTelephonyTimeZoneSuggestion expectedScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(
                            lowQualitySuggestion2, testCase.expectedScore);
            script.verifyLatestQualifiedTelephonySuggestionReceived(
                    SLOT_INDEX1, expectedScoredSuggestion);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
        }
    }

    /**
     * Confirms that toggling the auto time zone detection setting has the expected behavior when
     * the strategy is "opinionated" when using telephony auto detection.
     */
    @Test
    public void testTogglingAutoDetection_autoTelephony() {
        Script script = new Script();

        for (TelephonyTestCase testCase : TELEPHONY_TEST_CASES) {
            // Start with the device in a known state.
            script.initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                    .simulateConfigurationInternalChange(CONFIG_AUTO_DISABLED_GEO_DISABLED)
                    .resetConfigurationTracking();

            TelephonyTimeZoneSuggestion suggestion =
                    testCase.createSuggestion(SLOT_INDEX1, "Europe/London");
            script.simulateTelephonyTimeZoneSuggestion(suggestion);

            // When time zone detection is not enabled, the time zone suggestion will not be set
            // regardless of the score.
            script.verifyTimeZoneNotChanged();

            // Assert internal service state.
            QualifiedTelephonyTimeZoneSuggestion expectedScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(suggestion, testCase.expectedScore);
            script.verifyLatestQualifiedTelephonySuggestionReceived(
                    SLOT_INDEX1, expectedScoredSuggestion);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // Toggling the time zone setting on should cause the device setting to be set.
            script.simulateSetAutoMode(true);

            // When time zone detection is already enabled the suggestion (if it scores highly
            // enough) should be set immediately.
            if (testCase.expectedScore >= TELEPHONY_SCORE_USAGE_THRESHOLD) {
                script.verifyTimeZoneChangedAndReset(suggestion);
            } else {
                script.verifyTimeZoneNotChanged();
            }

            // Assert internal service state.
            script.verifyLatestQualifiedTelephonySuggestionReceived(
                    SLOT_INDEX1, expectedScoredSuggestion);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // Toggling the time zone setting should off should do nothing.
            script.simulateSetAutoMode(false)
                    .verifyTimeZoneNotChanged();

            // Assert internal service state.
            script.verifyLatestQualifiedTelephonySuggestionReceived(
                    SLOT_INDEX1, expectedScoredSuggestion);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
        }
    }

    @Test
    public void testTelephonySuggestionsSingleSlotId() {
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_DISABLED)
                .resetConfigurationTracking();

        for (TelephonyTestCase testCase : TELEPHONY_TEST_CASES) {
            makeSlotIndex1SuggestionAndCheckState(script, testCase);
        }

        /*
         * This is the same test as above but the test cases are in
         * reverse order of their expected score. New suggestions always replace previous ones:
         * there's effectively no history and so ordering shouldn't make any difference.
         */

        // Each test case will have the same or lower score than the last.
        List<TelephonyTestCase> descendingCasesByScore = Arrays.asList(TELEPHONY_TEST_CASES);
        Collections.reverse(descendingCasesByScore);

        for (TelephonyTestCase testCase : descendingCasesByScore) {
            makeSlotIndex1SuggestionAndCheckState(script, testCase);
        }
    }

    private void makeSlotIndex1SuggestionAndCheckState(Script script, TelephonyTestCase testCase) {
        // Give the next suggestion a different zone from the currently set device time zone;
        String currentZoneId = mFakeEnvironment.getDeviceTimeZone();
        String suggestionZoneId =
                "Europe/London".equals(currentZoneId) ? "Europe/Paris" : "Europe/London";
        TelephonyTimeZoneSuggestion zoneSlotIndex1Suggestion =
                testCase.createSuggestion(SLOT_INDEX1, suggestionZoneId);
        QualifiedTelephonyTimeZoneSuggestion expectedZoneSlotIndex1ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(
                        zoneSlotIndex1Suggestion, testCase.expectedScore);

        script.simulateTelephonyTimeZoneSuggestion(zoneSlotIndex1Suggestion);
        if (testCase.expectedScore >= TELEPHONY_SCORE_USAGE_THRESHOLD) {
            script.verifyTimeZoneChangedAndReset(zoneSlotIndex1Suggestion);
        } else {
            script.verifyTimeZoneNotChanged();
        }

        // Assert internal service state.
        script.verifyLatestQualifiedTelephonySuggestionReceived(
                SLOT_INDEX1, expectedZoneSlotIndex1ScoredSuggestion);
        assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
    }

    /**
     * Tries a set of test cases to see if the slotIndex with the lowest numeric value is given
     * preference. This test also confirms that the time zone setting would only be set if a
     * suggestion is of sufficient quality.
     */
    @Test
    public void testTelephonySuggestionMultipleSlotIndexSuggestionScoringAndSlotIndexBias() {
        String[] zoneIds = { "Europe/London", "Europe/Paris" };
        TelephonyTimeZoneSuggestion emptySlotIndex1Suggestion = createEmptySlotIndex1Suggestion();
        TelephonyTimeZoneSuggestion emptySlotIndex2Suggestion = createEmptySlotIndex2Suggestion();
        QualifiedTelephonyTimeZoneSuggestion expectedEmptySlotIndex1ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(emptySlotIndex1Suggestion,
                        TELEPHONY_SCORE_NONE);
        QualifiedTelephonyTimeZoneSuggestion expectedEmptySlotIndex2ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(emptySlotIndex2Suggestion,
                        TELEPHONY_SCORE_NONE);

        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_DISABLED)
                .resetConfigurationTracking()

                // Initialize the latest suggestions as empty so we don't need to worry about nulls
                // below for the first loop.
                .simulateTelephonyTimeZoneSuggestion(emptySlotIndex1Suggestion)
                .simulateTelephonyTimeZoneSuggestion(emptySlotIndex2Suggestion)
                .resetConfigurationTracking();

        for (TelephonyTestCase testCase : TELEPHONY_TEST_CASES) {
            TelephonyTimeZoneSuggestion zoneSlotIndex1Suggestion =
                    testCase.createSuggestion(SLOT_INDEX1, zoneIds[0]);
            TelephonyTimeZoneSuggestion zoneSlotIndex2Suggestion =
                    testCase.createSuggestion(SLOT_INDEX2, zoneIds[1]);
            QualifiedTelephonyTimeZoneSuggestion expectedZoneSlotIndex1ScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(zoneSlotIndex1Suggestion,
                            testCase.expectedScore);
            QualifiedTelephonyTimeZoneSuggestion expectedZoneSlotIndex2ScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(zoneSlotIndex2Suggestion,
                            testCase.expectedScore);

            // Start the test by making a suggestion for slotIndex1.
            script.simulateTelephonyTimeZoneSuggestion(zoneSlotIndex1Suggestion);
            if (testCase.expectedScore >= TELEPHONY_SCORE_USAGE_THRESHOLD) {
                script.verifyTimeZoneChangedAndReset(zoneSlotIndex1Suggestion);
            } else {
                script.verifyTimeZoneNotChanged();
            }

            // Assert internal service state.
            script.verifyLatestQualifiedTelephonySuggestionReceived(
                    SLOT_INDEX1, expectedZoneSlotIndex1ScoredSuggestion)
                    .verifyLatestQualifiedTelephonySuggestionReceived(
                            SLOT_INDEX2, expectedEmptySlotIndex2ScoredSuggestion);
            assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // SlotIndex2 then makes an alternative suggestion with an identical score. SlotIndex1's
            // suggestion should still "win" if it is above the required threshold.
            script.simulateTelephonyTimeZoneSuggestion(zoneSlotIndex2Suggestion);
            script.verifyTimeZoneNotChanged();

            // Assert internal service state.
            script.verifyLatestQualifiedTelephonySuggestionReceived(
                            SLOT_INDEX1, expectedZoneSlotIndex1ScoredSuggestion)
                    .verifyLatestQualifiedTelephonySuggestionReceived(
                            SLOT_INDEX2, expectedZoneSlotIndex2ScoredSuggestion);
            // SlotIndex1 should always beat slotIndex2, all other things being equal.
            assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // Withdrawing slotIndex1's suggestion should leave slotIndex2 as the new winner. Since
            // the zoneId is different, the time zone setting should be updated if the score is high
            // enough.
            script.simulateTelephonyTimeZoneSuggestion(emptySlotIndex1Suggestion);
            if (testCase.expectedScore >= TELEPHONY_SCORE_USAGE_THRESHOLD) {
                script.verifyTimeZoneChangedAndReset(zoneSlotIndex2Suggestion);
            } else {
                script.verifyTimeZoneNotChanged();
            }

            // Assert internal service state.
            script.verifyLatestQualifiedTelephonySuggestionReceived(
                            SLOT_INDEX1, expectedEmptySlotIndex1ScoredSuggestion)
                    .verifyLatestQualifiedTelephonySuggestionReceived(
                            SLOT_INDEX2, expectedZoneSlotIndex2ScoredSuggestion);
            assertEquals(expectedZoneSlotIndex2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // Reset the state for the next loop.
            script.simulateTelephonyTimeZoneSuggestion(emptySlotIndex2Suggestion)
                    .verifyTimeZoneNotChanged();
            script.verifyLatestQualifiedTelephonySuggestionReceived(
                            SLOT_INDEX1, expectedEmptySlotIndex1ScoredSuggestion)
                    .verifyLatestQualifiedTelephonySuggestionReceived(
                            SLOT_INDEX2, expectedEmptySlotIndex2ScoredSuggestion);
        }
    }

    /**
     * The {@link TimeZoneDetectorStrategyImpl.Environment} is left to detect whether changing the
     * time zone is actually necessary. This test proves that the strategy doesn't assume it knows
     * the current settings.
     */
    @Test
    public void testTelephonySuggestionStrategyDoesNotAssumeCurrentSetting_autoTelephony() {
        Script script = new Script()
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_DISABLED)
                .resetConfigurationTracking();

        TelephonyTestCase testCase = newTelephonyTestCase(
                MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE, TELEPHONY_SCORE_HIGH);
        TelephonyTimeZoneSuggestion losAngelesSuggestion =
                testCase.createSuggestion(SLOT_INDEX1, "America/Los_Angeles");
        TelephonyTimeZoneSuggestion newYorkSuggestion =
                testCase.createSuggestion(SLOT_INDEX1, "America/New_York");

        // Initialization.
        script.simulateTelephonyTimeZoneSuggestion(losAngelesSuggestion)
                .verifyTimeZoneChangedAndReset(losAngelesSuggestion);
        // Suggest it again - it should not be set because it is already set.
        script.simulateTelephonyTimeZoneSuggestion(losAngelesSuggestion)
                .verifyTimeZoneNotChanged();

        // Toggling time zone detection should set the device time zone only if the current setting
        // value is different from the most recent telephony suggestion.
        script.simulateSetAutoMode(false)
                .verifyTimeZoneNotChanged()
                .simulateSetAutoMode(true)
                .verifyTimeZoneNotChanged();

        // Simulate a user turning auto detection off, a new suggestion being made while auto
        // detection is off, and the user turning it on again.
        script.simulateSetAutoMode(false)
                .simulateTelephonyTimeZoneSuggestion(newYorkSuggestion)
                .verifyTimeZoneNotChanged();
        // Latest suggestion should be used.
        script.simulateSetAutoMode(true)
                .verifyTimeZoneChangedAndReset(newYorkSuggestion);
    }

    @Test
    public void testManualSuggestion_unrestricted_autoDetectionEnabled_autoTelephony() {
        checkManualSuggestion_unrestricted_autoDetectionEnabled(false /* geoDetectionEnabled */);
    }

    @Test
    public void testManualSuggestion_unrestricted_autoDetectionEnabled_autoGeo() {
        checkManualSuggestion_unrestricted_autoDetectionEnabled(true /* geoDetectionEnabled */);
    }

    private void checkManualSuggestion_unrestricted_autoDetectionEnabled(
            boolean geoDetectionEnabled) {
        ConfigurationInternal geoTzEnabledConfig =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED_GEO_DISABLED)
                        .setGeoDetectionEnabledSetting(geoDetectionEnabled)
                        .build();
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(geoTzEnabledConfig)
                .resetConfigurationTracking();

        // Auto time zone detection is enabled so the manual suggestion should be ignored.
        boolean bypassUserPolicyChecks = false;
        boolean expectedResult = false;
        script.simulateManualTimeZoneSuggestion(USER_ID, createManualSuggestion("Europe/Paris"),
                        bypassUserPolicyChecks, expectedResult)
                .verifyTimeZoneNotChanged();

        assertNull(mTimeZoneDetectorStrategy.getLatestManualSuggestion());
    }

    @Test
    public void testManualSuggestion_autoDetectNotSupported() {
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(CONFIG_AUTO_DETECT_NOT_SUPPORTED)
                .resetConfigurationTracking();

        // Unrestricted users have the capability.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Europe/Paris");
        boolean bypassUserPolicyChecks = false;
        boolean expectedResult = true;
        script.simulateManualTimeZoneSuggestion(
                USER_ID, manualSuggestion, bypassUserPolicyChecks, expectedResult)
                .verifyTimeZoneChangedAndReset(manualSuggestion);

        assertEquals(manualSuggestion, mTimeZoneDetectorStrategy.getLatestManualSuggestion());
    }

    @Test
    @Parameters({ "true,true", "true,false", "false,true", "false,false" })
    public void testManualSuggestion_autoTimeEnabled_userRestrictions(
            boolean userConfigAllowed, boolean bypassUserPolicyChecks) {
        ConfigurationInternal config =
                new ConfigurationInternal.Builder(CONFIG_USER_RESTRICTED_AUTO_ENABLED)
                        .setUserConfigAllowed(userConfigAllowed)
                        .build();
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(config)
                .resetConfigurationTracking();

        // User is restricted so the manual suggestion should be ignored.
        boolean expectedResult = false;
        script.simulateManualTimeZoneSuggestion(USER_ID, createManualSuggestion("Europe/Paris"),
                        bypassUserPolicyChecks, expectedResult)
                .verifyTimeZoneNotChanged();

        assertNull(mTimeZoneDetectorStrategy.getLatestManualSuggestion());
    }

    @Test
    @Parameters({ "true,true", "true,false", "false,true", "false,false" })
    public void testManualSuggestion_autoTimeDisabled_userRestrictions(
            boolean userConfigAllowed, boolean bypassUserPolicyChecks) {
        ConfigurationInternal config =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED_GEO_DISABLED)
                        .setUserConfigAllowed(userConfigAllowed)
                        .build();
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(config)
                .resetConfigurationTracking();

        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Europe/Paris");
        boolean expectedResult = userConfigAllowed || bypassUserPolicyChecks;
        script.simulateManualTimeZoneSuggestion(
                        USER_ID, manualSuggestion, bypassUserPolicyChecks, expectedResult);
        if (expectedResult) {
            script.verifyTimeZoneChangedAndReset(manualSuggestion);
            assertEquals(manualSuggestion, mTimeZoneDetectorStrategy.getLatestManualSuggestion());
        } else {
            script.verifyTimeZoneNotChanged();
            assertNull(mTimeZoneDetectorStrategy.getLatestManualSuggestion());
        }
    }

    @Test
    public void testLocationAlgorithmEvent_statusChangesOnly() {
        TestStateChangeListener stateChangeListener = new TestStateChangeListener();
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_ENABLED)
                .resetConfigurationTracking()
                .registerStateChangeListener(stateChangeListener);

        TimeZoneDetectorStatus expectedInitialDetectorStatus = new TimeZoneDetectorStatus(
                DETECTOR_STATUS_RUNNING,
                TELEPHONY_ALGORITHM_RUNNING_STATUS,
                LocationTimeZoneAlgorithmStatus.RUNNING_NOT_REPORTED);
        script.verifyCachedDetectorStatus(expectedInitialDetectorStatus);

        LocationTimeZoneAlgorithmStatus algorithmStatus1 = new LocationTimeZoneAlgorithmStatus(
                DETECTION_ALGORITHM_STATUS_RUNNING, PROVIDER_STATUS_NOT_READY, null,
                PROVIDER_STATUS_NOT_PRESENT, null);
        LocationTimeZoneAlgorithmStatus algorithmStatus2 = new LocationTimeZoneAlgorithmStatus(
                DETECTION_ALGORITHM_STATUS_RUNNING, PROVIDER_STATUS_NOT_PRESENT, null,
                PROVIDER_STATUS_NOT_PRESENT, null);
        assertNotEquals(algorithmStatus1, algorithmStatus2);

        {
            LocationAlgorithmEvent locationAlgorithmEvent =
                    new LocationAlgorithmEvent(algorithmStatus1, null);
            script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                    .verifyTimeZoneNotChanged();

            assertStateChangeNotificationsSent(stateChangeListener, 1);

            // Assert internal service state.
            TimeZoneDetectorStatus expectedDetectorStatus = new TimeZoneDetectorStatus(
                    DETECTOR_STATUS_RUNNING,
                    TELEPHONY_ALGORITHM_RUNNING_STATUS,
                    algorithmStatus1);
            script.verifyCachedDetectorStatus(expectedDetectorStatus)
                    .verifyLatestLocationAlgorithmEventReceived(locationAlgorithmEvent);

            // Repeat the event to demonstrate the state change notifier is not triggered.
            script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                    .verifyTimeZoneNotChanged();

            assertStateChangeNotificationsSent(stateChangeListener, 0);

            // Assert internal service state.
            script.verifyCachedDetectorStatus(expectedDetectorStatus)
                    .verifyLatestLocationAlgorithmEventReceived(locationAlgorithmEvent);
        }

        {
            LocationAlgorithmEvent locationAlgorithmEvent =
                    new LocationAlgorithmEvent(algorithmStatus2, null);
            script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                    .verifyTimeZoneNotChanged();

            assertStateChangeNotificationsSent(stateChangeListener, 1);

            // Assert internal service state.
            TimeZoneDetectorStatus expectedDetectorStatus = new TimeZoneDetectorStatus(
                    DETECTOR_STATUS_RUNNING,
                    TELEPHONY_ALGORITHM_RUNNING_STATUS,
                    algorithmStatus2);
            script.verifyCachedDetectorStatus(expectedDetectorStatus)
                    .verifyLatestLocationAlgorithmEventReceived(locationAlgorithmEvent);

            // Repeat the event to demonstrate the state change notifier is not triggered.
            script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                    .verifyTimeZoneNotChanged();

            assertStateChangeNotificationsSent(stateChangeListener, 0);

            // Assert internal service state.
            script.verifyCachedDetectorStatus(expectedDetectorStatus)
                    .verifyLatestLocationAlgorithmEventReceived(locationAlgorithmEvent);
        }
    }

    @Test
    public void testLocationAlgorithmEvent_uncertain() {
        TestStateChangeListener stateChangeListener = new TestStateChangeListener();
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_ENABLED)
                .resetConfigurationTracking()
                .registerStateChangeListener(stateChangeListener);

        LocationAlgorithmEvent locationAlgorithmEvent = createUncertainLocationAlgorithmEvent();
        script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                .verifyTimeZoneNotChanged();

        assertStateChangeNotificationsSent(stateChangeListener, 1);

        // Assert internal service state.
        TimeZoneDetectorStatus expectedDetectorStatus = new TimeZoneDetectorStatus(
                DETECTOR_STATUS_RUNNING,
                TELEPHONY_ALGORITHM_RUNNING_STATUS,
                locationAlgorithmEvent.getAlgorithmStatus());
        script.verifyCachedDetectorStatus(expectedDetectorStatus)
                .verifyLatestLocationAlgorithmEventReceived(locationAlgorithmEvent);

        // Repeat the event to demonstrate the state change notifier is not triggered.
        script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                .verifyTimeZoneNotChanged();

        // Detector remains running and location algorithm is still uncertain so nothing to report.
        assertStateChangeNotificationsSent(stateChangeListener, 0);

        // Assert internal service state.
        script.verifyCachedDetectorStatus(expectedDetectorStatus)
                .verifyLatestLocationAlgorithmEventReceived(locationAlgorithmEvent);
    }

    @Test
    public void testLocationAlgorithmEvent_noZones() {
        TestStateChangeListener stateChangeListener = new TestStateChangeListener();
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_ENABLED)
                .resetConfigurationTracking()
                .registerStateChangeListener(stateChangeListener);

        LocationAlgorithmEvent locationAlgorithmEvent = createCertainLocationAlgorithmEvent();
        script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                .verifyTimeZoneNotChanged();

        assertStateChangeNotificationsSent(stateChangeListener, 1);

        // Assert internal service state.
        TimeZoneDetectorStatus expectedDetectorStatus = new TimeZoneDetectorStatus(
                DETECTOR_STATUS_RUNNING,
                TELEPHONY_ALGORITHM_RUNNING_STATUS,
                locationAlgorithmEvent.getAlgorithmStatus());
        script.verifyCachedDetectorStatus(expectedDetectorStatus)
                .verifyLatestLocationAlgorithmEventReceived(locationAlgorithmEvent);

        // Repeat the event to demonstrate the state change notifier is not triggered.
        script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                .verifyTimeZoneNotChanged();

        assertStateChangeNotificationsSent(stateChangeListener, 0);

        // Assert internal service state.
        script.verifyCachedDetectorStatus(expectedDetectorStatus)
                .verifyLatestLocationAlgorithmEventReceived(locationAlgorithmEvent);
    }

    @Test
    public void testLocationAlgorithmEvent_oneZone() {
        TestStateChangeListener stateChangeListener = new TestStateChangeListener();
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_ENABLED)
                .resetConfigurationTracking()
                .registerStateChangeListener(stateChangeListener);

        LocationAlgorithmEvent locationAlgorithmEvent =
                createCertainLocationAlgorithmEvent("Europe/London");
        script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                .verifyTimeZoneChangedAndReset(locationAlgorithmEvent);

        assertStateChangeNotificationsSent(stateChangeListener, 1);

        // Assert internal service state.
        TimeZoneDetectorStatus expectedDetectorStatus = new TimeZoneDetectorStatus(
                DETECTOR_STATUS_RUNNING,
                TELEPHONY_ALGORITHM_RUNNING_STATUS,
                locationAlgorithmEvent.getAlgorithmStatus());
        script.verifyCachedDetectorStatus(expectedDetectorStatus)
                .verifyLatestLocationAlgorithmEventReceived(locationAlgorithmEvent);

        // Repeat the event to demonstrate the state change notifier is not triggered.
        script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                .verifyTimeZoneNotChanged();

        assertStateChangeNotificationsSent(stateChangeListener, 0);

        // Assert internal service state.
        script.verifyCachedDetectorStatus(expectedDetectorStatus)
                .verifyLatestLocationAlgorithmEventReceived(locationAlgorithmEvent);
    }

    /**
     * In the current implementation, the first zone ID is always used unless the device is set to
     * one of the other options. This is "stickiness" - the device favors the zone it is currently
     * set to until that unambiguously can't be correct.
     */
    @Test
    public void testLocationAlgorithmEvent_multiZone() {
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_ENABLED)
                .resetConfigurationTracking();

        LocationAlgorithmEvent londonOnlyEvent =
                createCertainLocationAlgorithmEvent("Europe/London");
        script.simulateLocationAlgorithmEvent(londonOnlyEvent)
                .verifyTimeZoneChangedAndReset(londonOnlyEvent)
                .verifyLatestLocationAlgorithmEventReceived(londonOnlyEvent);

        // Confirm bias towards the current device zone when there's multiple zones to choose from.
        LocationAlgorithmEvent londonOrParisEvent =
                createCertainLocationAlgorithmEvent("Europe/Paris", "Europe/London");
        script.simulateLocationAlgorithmEvent(londonOrParisEvent)
                .verifyTimeZoneNotChanged()
                .verifyLatestLocationAlgorithmEventReceived(londonOrParisEvent);

        LocationAlgorithmEvent parisOnlyEvent = createCertainLocationAlgorithmEvent("Europe/Paris");
        script.simulateLocationAlgorithmEvent(parisOnlyEvent)
                .verifyTimeZoneChangedAndReset(parisOnlyEvent)
                .verifyLatestLocationAlgorithmEventReceived(parisOnlyEvent);

        // Now the suggestion that previously left the device on Europe/London will leave the device
        // on Europe/Paris.
        script.simulateLocationAlgorithmEvent(londonOrParisEvent)
                .verifyTimeZoneNotChanged()
                .verifyLatestLocationAlgorithmEventReceived(londonOrParisEvent);
    }

    /**
     * Confirms that changing the geolocation time zone detection enabled setting has the expected
     * behavior, i.e. immediately recompute the detected time zone using different signals.
     */
    @Test
    public void testChangingGeoDetectionEnabled() {
        TestStateChangeListener stateChangeListener = new TestStateChangeListener();
        LocationAlgorithmEvent locationAlgorithmEvent =
                createCertainLocationAlgorithmEvent("Europe/London");
        TelephonyTimeZoneSuggestion telephonySuggestion = createTelephonySuggestion(
                SLOT_INDEX1, MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                "Europe/Paris");

        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(CONFIG_AUTO_DISABLED_GEO_DISABLED)
                .resetConfigurationTracking()
                .registerStateChangeListener(stateChangeListener);

        // Add suggestions. Nothing should happen as time zone detection is disabled.
        script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                .verifyTimeZoneNotChanged()
                .verifyLatestLocationAlgorithmEventReceived(locationAlgorithmEvent);

        // A detector status change is considered a "state change".
        assertStateChangeNotificationsSent(stateChangeListener, 1);

        script.simulateTelephonyTimeZoneSuggestion(telephonySuggestion)
                .verifyTimeZoneNotChanged()
                .verifyLatestTelephonySuggestionReceived(SLOT_INDEX1, telephonySuggestion);

        assertStateChangeNotificationsSent(stateChangeListener, 0);

        // Toggling the time zone detection enabled setting on should cause the device setting to be
        // set from the telephony signal, as we've started with geolocation time zone detection
        // disabled.
        script.simulateSetAutoMode(true)
                .verifyTimeZoneChangedAndReset(telephonySuggestion);

        // A configuration change is considered a "state change".
        assertStateChangeNotificationsSent(stateChangeListener, 1);

        // Changing the detection to enable geo detection will cause the device tz setting to
        // change to use the latest geolocation suggestion.
        script.simulateSetGeoDetectionEnabled(true)
                .verifyTimeZoneChangedAndReset(locationAlgorithmEvent);

        // A configuration change is considered a "state change".
        assertStateChangeNotificationsSent(stateChangeListener, 1);

        // Changing the detection to disable geo detection should cause the device tz setting to
        // change to the telephony suggestion.
        script.simulateSetGeoDetectionEnabled(false)
                .verifyTimeZoneChangedAndReset(telephonySuggestion)
                .verifyLatestLocationAlgorithmEventReceived(locationAlgorithmEvent);

        // A configuration change is considered a "state change".
        assertStateChangeNotificationsSent(stateChangeListener, 1);
    }

    @Test
    public void testTelephonyFallback_enableTelephonyTimeZoneFallbackCalled() {
        ConfigurationInternal config = new ConfigurationInternal.Builder(
                CONFIG_AUTO_ENABLED_GEO_ENABLED)
                .setTelephonyFallbackSupported(true)
                .build();

        Script script = new Script()
                .initializeClock(ARBITRARY_ELAPSED_REALTIME_MILLIS)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(config)
                .resetConfigurationTracking();

        // Confirm initial state is as expected.
        script.verifyTelephonyFallbackIsEnabled(true)
                .verifyTimeZoneNotChanged();

        // Although geolocation detection is enabled, telephony fallback should be used initially
        // and until a suitable "certain" geolocation suggestion is received.
        {
            TelephonyTimeZoneSuggestion telephonySuggestion = createTelephonySuggestion(
                    SLOT_INDEX1, MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                    "Europe/Paris");
            script.simulateIncrementClock()
                    .simulateTelephonyTimeZoneSuggestion(telephonySuggestion)
                    .verifyTimeZoneChangedAndReset(telephonySuggestion)
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Receiving an "uncertain" geolocation suggestion should have no effect.
        {
            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent locationAlgorithmEvent = createUncertainLocationAlgorithmEvent();
            script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Receiving a "certain" geolocation suggestion should disable telephony fallback mode.
        {
            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent locationAlgorithmEvent =
                    createCertainLocationAlgorithmEvent("Europe/London");
            script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                    .verifyTimeZoneChangedAndReset(locationAlgorithmEvent)
                    .verifyTelephonyFallbackIsEnabled(false);
        }

        // Used to record the last telephony suggestion received, which will be used when fallback
        // takes place.
        TelephonyTimeZoneSuggestion lastTelephonySuggestion;

        // Telephony suggestions should now be ignored and geolocation detection is "in control".
        {
            TelephonyTimeZoneSuggestion telephonySuggestion = createTelephonySuggestion(
                    SLOT_INDEX1, MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                    "Europe/Berlin");
            script.simulateIncrementClock()
                    .simulateTelephonyTimeZoneSuggestion(telephonySuggestion)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(false);
            lastTelephonySuggestion = telephonySuggestion;
        }

        // Geolocation suggestions should continue to be used as normal (previous telephony
        // suggestions are not used, even when the geolocation suggestion is uncertain).
        {
            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent certainLocationAlgorithmEvent =
                    createCertainLocationAlgorithmEvent("Europe/Rome");
            script.simulateLocationAlgorithmEvent(certainLocationAlgorithmEvent)
                    .verifyTimeZoneChangedAndReset(certainLocationAlgorithmEvent)
                    .verifyTelephonyFallbackIsEnabled(false);

            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent uncertainLocationAlgorithmEvent =
                    createUncertainLocationAlgorithmEvent();
            script.simulateLocationAlgorithmEvent(uncertainLocationAlgorithmEvent)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(false);

            script.simulateIncrementClock()
                    .simulateLocationAlgorithmEvent(certainLocationAlgorithmEvent)
                    // No change needed, device will already be set to Europe/Rome.
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(false);
        }

        // Enable telephony fallback. Nothing will change, because the geolocation is still certain,
        // but fallback will remain enabled.
        {
            script.simulateIncrementClock()
                    .simulateEnableTelephonyFallback()
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Make the geolocation algorithm uncertain.
        {
            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent locationAlgorithmEvent = createUncertainLocationAlgorithmEvent();
            script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                    .verifyTimeZoneChangedAndReset(lastTelephonySuggestion)
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Make the geolocation algorithm certain, disabling telephony fallback.
        {
            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent locationAlgorithmEvent =
                    createCertainLocationAlgorithmEvent("Europe/Lisbon");
            script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                    .verifyTimeZoneChangedAndReset(locationAlgorithmEvent)
                    .verifyTelephonyFallbackIsEnabled(false);

        }

        // Demonstrate what happens when geolocation is uncertain when telephony fallback is
        // enabled.
        {
            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent locationAlgorithmEvent = createUncertainLocationAlgorithmEvent();
            script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(false)
                    .simulateEnableTelephonyFallback()
                    .verifyTimeZoneChangedAndReset(lastTelephonySuggestion)
                    .verifyTelephonyFallbackIsEnabled(true);
        }
    }

    @Test
    public void testTelephonyFallback_locationAlgorithmEventSuggestsFallback() {
        ConfigurationInternal config = new ConfigurationInternal.Builder(
                CONFIG_AUTO_ENABLED_GEO_ENABLED)
                .setTelephonyFallbackSupported(true)
                .build();

        Script script = new Script()
                .initializeClock(ARBITRARY_ELAPSED_REALTIME_MILLIS)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(config)
                .resetConfigurationTracking();

        // Confirm initial state is as expected.
        script.verifyTelephonyFallbackIsEnabled(true)
                .verifyTimeZoneNotChanged();

        // Although geolocation detection is enabled, telephony fallback should be used initially
        // and until a suitable "certain" geolocation suggestion is received.
        {
            TelephonyTimeZoneSuggestion telephonySuggestion = createTelephonySuggestion(
                    SLOT_INDEX1, MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                    "Europe/Paris");
            script.simulateIncrementClock()
                    .simulateTelephonyTimeZoneSuggestion(telephonySuggestion)
                    .verifyTimeZoneChangedAndReset(telephonySuggestion)
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Receiving an "uncertain" geolocation suggestion without a status should have no effect.
        {
            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent locationAlgorithmEvent = createUncertainLocationAlgorithmEvent();
            script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Receiving a "certain" geolocation suggestion should disable telephony fallback mode.
        {
            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent locationAlgorithmEvent =
                    createCertainLocationAlgorithmEvent("Europe/London");
            script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                    .verifyTimeZoneChangedAndReset(locationAlgorithmEvent)
                    .verifyTelephonyFallbackIsEnabled(false);
        }

        // Used to record the last telephony suggestion received, which will be used when fallback
        // takes place.
        TelephonyTimeZoneSuggestion lastTelephonySuggestion;

        // Telephony suggestions should now be ignored and geolocation detection is "in control".
        {
            TelephonyTimeZoneSuggestion telephonySuggestion = createTelephonySuggestion(
                    SLOT_INDEX1, MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                    "Europe/Berlin");
            script.simulateIncrementClock()
                    .simulateTelephonyTimeZoneSuggestion(telephonySuggestion)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(false);
            lastTelephonySuggestion = telephonySuggestion;
        }

        // Geolocation suggestions should continue to be used as normal (previous telephony
        // suggestions are not used, even when the geolocation suggestion is uncertain).
        {
            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent certainLocationAlgorithmEvent =
                    createCertainLocationAlgorithmEvent("Europe/Rome");
            script.simulateLocationAlgorithmEvent(certainLocationAlgorithmEvent)
                    .verifyTimeZoneChangedAndReset(certainLocationAlgorithmEvent)
                    .verifyTelephonyFallbackIsEnabled(false);

            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent uncertainLocationAlgorithmEvent =
                    createUncertainLocationAlgorithmEvent();
            script.simulateLocationAlgorithmEvent(uncertainLocationAlgorithmEvent)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(false);

            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent certainLocationAlgorithmEvent2 =
                    createCertainLocationAlgorithmEvent("Europe/Rome");
            script.simulateLocationAlgorithmEvent(certainLocationAlgorithmEvent2)
                    // No change needed, device will already be set to Europe/Rome.
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(false);
        }

        // Enable telephony fallback via a LocationAlgorithmEvent containing an "uncertain"
        // suggestion.
        {
            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            TimeZoneProviderStatus primaryProviderReportedStatus =
                    new TimeZoneProviderStatus.Builder()
                            .setLocationDetectionDependencyStatus(
                                    DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS)
                            .setConnectivityDependencyStatus(DEPENDENCY_STATUS_UNKNOWN)
                            .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_UNKNOWN)
                            .build();
            LocationAlgorithmEvent uncertainEventBlockedBySettings =
                    createUncertainLocationAlgorithmEvent(primaryProviderReportedStatus);
            script.simulateLocationAlgorithmEvent(uncertainEventBlockedBySettings)
                    .verifyTimeZoneChangedAndReset(lastTelephonySuggestion)
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Make the geolocation algorithm certain, disabling telephony fallback.
        {
            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent locationAlgorithmEvent =
                    createCertainLocationAlgorithmEvent("Europe/Lisbon");
            script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                    .verifyTimeZoneChangedAndReset(locationAlgorithmEvent)
                    .verifyTelephonyFallbackIsEnabled(false);
        }
    }

    @Test
    public void testTelephonyFallback_noTelephonySuggestionToFallBackTo() {
        ConfigurationInternal config = new ConfigurationInternal.Builder(
                CONFIG_AUTO_ENABLED_GEO_ENABLED)
                .setTelephonyFallbackSupported(true)
                .build();

        Script script = new Script()
                .initializeClock(ARBITRARY_ELAPSED_REALTIME_MILLIS)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(config)
                .resetConfigurationTracking();

        // Confirm initial state is as expected.
        script.verifyTelephonyFallbackIsEnabled(true)
                .verifyTimeZoneNotChanged();

        // Receiving an "uncertain" geolocation suggestion should have no effect.
        {
            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent locationAlgorithmEvent = createUncertainLocationAlgorithmEvent();
            script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Make an uncertain geolocation suggestion, there is no telephony suggestion to fall back
        // to
        {
            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent locationAlgorithmEvent = createUncertainLocationAlgorithmEvent();
            script.simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Similar to the case above, but force a fallback attempt after making a "certain"
        // geolocation suggestion.
        // Geolocation suggestions should continue to be used as normal (previous telephony
        // suggestions are not used, even when the geolocation suggestion is uncertain).
        {
            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent certainEvent =
                    createCertainLocationAlgorithmEvent("Europe/Rome");
            script.simulateLocationAlgorithmEvent(certainEvent)
                    .verifyTimeZoneChangedAndReset(certainEvent)
                    .verifyTelephonyFallbackIsEnabled(false);

            // Increment the clock before creating the event: the clock's value is used by the event
            script.simulateIncrementClock();
            LocationAlgorithmEvent uncertainEvent = createUncertainLocationAlgorithmEvent();
            script.simulateLocationAlgorithmEvent(uncertainEvent)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(false);

            script.simulateIncrementClock()
                    .simulateEnableTelephonyFallback()
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(true);
        }
    }

    @Test
    public void testGetTimeZoneState() {
        Script script = new Script()
                .initializeClock(ARBITRARY_ELAPSED_REALTIME_MILLIS)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(CONFIG_AUTO_DISABLED_GEO_DISABLED)
                .resetConfigurationTracking();

        String timeZoneId = "Europe/London";

        // When confidence is low, the user should confirm.
        script.initializeTimeZoneSetting(timeZoneId, TIME_ZONE_CONFIDENCE_LOW);
        assertEquals(new TimeZoneState(timeZoneId, true),
                mTimeZoneDetectorStrategy.getTimeZoneState());

        // When confidence is high, no need for the user to confirm.
        script.initializeTimeZoneSetting(timeZoneId, TIME_ZONE_CONFIDENCE_HIGH);

        assertEquals(new TimeZoneState(timeZoneId, false),
                mTimeZoneDetectorStrategy.getTimeZoneState());
    }

    @Test
    public void testSetTimeZoneState() {
        Script script = new Script()
                .initializeClock(ARBITRARY_ELAPSED_REALTIME_MILLIS)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(CONFIG_AUTO_DISABLED_GEO_DISABLED)
                .resetConfigurationTracking();

        String timeZoneId = "Europe/London";
        boolean userShouldConfirmId = false;
        TimeZoneState state = new TimeZoneState(timeZoneId, userShouldConfirmId);
        mTimeZoneDetectorStrategy.setTimeZoneState(state);

        script.verifyTimeZoneChangedAndReset(timeZoneId, TIME_ZONE_CONFIDENCE_HIGH);
        assertEquals(state, mTimeZoneDetectorStrategy.getTimeZoneState());
    }

    @Test
    public void testConfirmTimeZone_autoDisabled() {
        testConfirmTimeZone(CONFIG_AUTO_DISABLED_GEO_DISABLED);
    }

    @Test
    public void testConfirmTimeZone_autoEnabled() {
        testConfirmTimeZone(CONFIG_AUTO_ENABLED_GEO_DISABLED);
    }

    private void testConfirmTimeZone(ConfigurationInternal config) {
        String timeZoneId = "Europe/London";
        Script script = new Script()
                .initializeClock(ARBITRARY_ELAPSED_REALTIME_MILLIS)
                .initializeTimeZoneSetting(timeZoneId, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(config)
                .resetConfigurationTracking();

        String incorrectTimeZoneId = "Europe/Paris";
        assertFalse(mTimeZoneDetectorStrategy.confirmTimeZone(incorrectTimeZoneId));
        script.verifyTimeZoneNotChanged();

        assertTrue(mTimeZoneDetectorStrategy.confirmTimeZone(timeZoneId));
        script.verifyTimeZoneChangedAndReset(timeZoneId, TIME_ZONE_CONFIDENCE_HIGH);

        assertTrue(mTimeZoneDetectorStrategy.confirmTimeZone(timeZoneId));
        // The strategy checks the current confidence and if it is already high it takes no action.
        script.verifyTimeZoneNotChanged();

        assertFalse(mTimeZoneDetectorStrategy.confirmTimeZone(incorrectTimeZoneId));
        script.verifyTimeZoneNotChanged();
    }

    @Test
    public void testGenerateMetricsState_enhancedMetricsCollection() {
        testGenerateMetricsState(true);
    }

    @Test
    public void testGenerateMetricsState_notEnhancedMetricsCollection() {
        testGenerateMetricsState(false);
    }

    private void testGenerateMetricsState(boolean enhancedMetricsCollection) {
        ConfigurationInternal expectedInternalConfig =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED_GEO_DISABLED)
                        .setEnhancedMetricsCollectionEnabled(enhancedMetricsCollection)
                        .build();
        String expectedDeviceTimeZoneId = "InitialZoneId";

        Script script = new Script()
                .initializeTimeZoneSetting(expectedDeviceTimeZoneId, TIME_ZONE_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(expectedInternalConfig)
                .resetConfigurationTracking();

        assertMetricsState(expectedInternalConfig, expectedDeviceTimeZoneId, null, null,
                null, MetricsTimeZoneDetectorState.DETECTION_MODE_MANUAL);

        // Make sure the manual suggestion is recorded.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Zone1");
        boolean bypassUserPolicyChecks = false;
        boolean expectedResult = true;
        script.simulateManualTimeZoneSuggestion(
                USER_ID, manualSuggestion, bypassUserPolicyChecks, expectedResult)
                .verifyTimeZoneChangedAndReset(manualSuggestion);
        expectedDeviceTimeZoneId = manualSuggestion.getZoneId();
        assertMetricsState(expectedInternalConfig, expectedDeviceTimeZoneId,
                manualSuggestion, null, null,
                MetricsTimeZoneDetectorState.DETECTION_MODE_MANUAL);

        // With time zone auto detection off, telephony and geo suggestions will be recorded.
        TelephonyTimeZoneSuggestion telephonySuggestion =
                createTelephonySuggestion(0 /* slotIndex */, MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                        QUALITY_SINGLE_ZONE, "Zone2");
        LocationAlgorithmEvent locationAlgorithmEvent =
                createCertainLocationAlgorithmEvent("Zone3", "Zone2");
        script.simulateTelephonyTimeZoneSuggestion(telephonySuggestion)
                .verifyTimeZoneNotChanged()
                .simulateLocationAlgorithmEvent(locationAlgorithmEvent)
                .verifyTimeZoneNotChanged();

        assertMetricsState(expectedInternalConfig, expectedDeviceTimeZoneId,
                manualSuggestion, telephonySuggestion, locationAlgorithmEvent,
                MetricsTimeZoneDetectorState.DETECTION_MODE_MANUAL);

        // Update the config and confirm that the config metrics state updates also.
        expectedInternalConfig = new ConfigurationInternal.Builder(expectedInternalConfig)
                .setAutoDetectionEnabledSetting(true)
                .setGeoDetectionEnabledSetting(true)
                .build();

        expectedDeviceTimeZoneId = locationAlgorithmEvent.getSuggestion().getZoneIds().get(0);
        script.simulateConfigurationInternalChange(expectedInternalConfig)
                .verifyTimeZoneChangedAndReset(expectedDeviceTimeZoneId, TIME_ZONE_CONFIDENCE_HIGH);
        assertMetricsState(expectedInternalConfig, expectedDeviceTimeZoneId,
                manualSuggestion, telephonySuggestion, locationAlgorithmEvent,
                MetricsTimeZoneDetectorState.DETECTION_MODE_GEO);
    }

    /**
     * Asserts that the information returned by {@link
     * TimeZoneDetectorStrategy#generateMetricsState()} matches expectations.
     */
    private void assertMetricsState(
            ConfigurationInternal expectedInternalConfig,
            String expectedDeviceTimeZoneId, ManualTimeZoneSuggestion expectedManualSuggestion,
            TelephonyTimeZoneSuggestion expectedTelephonySuggestion,
            LocationAlgorithmEvent expectedLocationAlgorithmEvent,
            int expectedDetectionMode) {

        MetricsTimeZoneDetectorState actualState = mTimeZoneDetectorStrategy.generateMetricsState();

        // Check the various feature state values are what we expect.
        assertFeatureStateMatchesConfig(expectedInternalConfig, actualState, expectedDetectionMode);

        OrdinalGenerator<String> tzIdOrdinalGenerator = new OrdinalGenerator<>(Function.identity());
        MetricsTimeZoneDetectorState expectedState =
                MetricsTimeZoneDetectorState.create(
                        tzIdOrdinalGenerator, expectedInternalConfig, expectedDeviceTimeZoneId,
                        expectedManualSuggestion, expectedTelephonySuggestion,
                        expectedLocationAlgorithmEvent);
        // Rely on MetricsTimeZoneDetectorState.equals() for time zone ID / ID ordinal comparisons.
        assertEquals(expectedState, actualState);
    }

    private static void assertFeatureStateMatchesConfig(ConfigurationInternal config,
            MetricsTimeZoneDetectorState actualState, int expectedDetectionMode) {
        assertEquals(config.isTelephonyDetectionSupported(),
                actualState.isTelephonyDetectionSupported());
        assertEquals(config.isGeoDetectionSupported(), actualState.isGeoDetectionSupported());
        assertEquals(config.isTelephonyFallbackSupported(),
                actualState.isTelephonyTimeZoneFallbackSupported());
        assertEquals(config.getAutoDetectionEnabledSetting(),
                actualState.getAutoDetectionEnabledSetting());
        assertEquals(config.getGeoDetectionEnabledSetting(),
                actualState.getGeoDetectionEnabledSetting());
        assertEquals(expectedDetectionMode, actualState.getDetectionMode());
    }

    private static ManualTimeZoneSuggestion createManualSuggestion(String zoneId) {
        return new ManualTimeZoneSuggestion(zoneId);
    }

    private static TelephonyTimeZoneSuggestion createTelephonySuggestion(
            int slotIndex, @MatchType int matchType, @Quality int quality, String zoneId) {
        return new TelephonyTimeZoneSuggestion.Builder(slotIndex)
                .setMatchType(matchType)
                .setQuality(quality)
                .setZoneId(zoneId)
                .build();
    }

    private static TelephonyTimeZoneSuggestion createEmptySlotIndex1Suggestion() {
        return new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX1).build();
    }

    private static TelephonyTimeZoneSuggestion createEmptySlotIndex2Suggestion() {
        return new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX2).build();
    }

    private LocationAlgorithmEvent createCertainLocationAlgorithmEvent(@NonNull String... zoneIds) {
        GeolocationTimeZoneSuggestion suggestion = createCertainGeolocationSuggestion(zoneIds);
        LocationTimeZoneAlgorithmStatus algorithmStatus = new LocationTimeZoneAlgorithmStatus(
                DETECTION_ALGORITHM_STATUS_RUNNING, PROVIDER_STATUS_IS_CERTAIN, null,
                PROVIDER_STATUS_NOT_PRESENT, null);
        LocationAlgorithmEvent event = new LocationAlgorithmEvent(algorithmStatus, suggestion);
        event.addDebugInfo("Test certain event");
        return event;
    }

    private LocationAlgorithmEvent createUncertainLocationAlgorithmEvent() {
        TimeZoneProviderStatus primaryProviderReportedStatus = null;
        return createUncertainLocationAlgorithmEvent(primaryProviderReportedStatus);
    }

    private LocationAlgorithmEvent createUncertainLocationAlgorithmEvent(
            TimeZoneProviderStatus primaryProviderReportedStatus) {
        GeolocationTimeZoneSuggestion suggestion = createUncertainGeolocationSuggestion();
        LocationTimeZoneAlgorithmStatus algorithmStatus = new LocationTimeZoneAlgorithmStatus(
                DETECTION_ALGORITHM_STATUS_RUNNING,
                PROVIDER_STATUS_IS_UNCERTAIN, primaryProviderReportedStatus,
                PROVIDER_STATUS_NOT_PRESENT, null);
        LocationAlgorithmEvent event = new LocationAlgorithmEvent(algorithmStatus, suggestion);
        event.addDebugInfo("Test uncertain event");
        return event;
    }

    private GeolocationTimeZoneSuggestion createUncertainGeolocationSuggestion() {
        return GeolocationTimeZoneSuggestion.createUncertainSuggestion(
                mFakeEnvironment.elapsedRealtimeMillis());
    }

    private GeolocationTimeZoneSuggestion createCertainGeolocationSuggestion(
            @NonNull String... zoneIds) {
        assertNotNull(zoneIds);

        return GeolocationTimeZoneSuggestion.createCertainSuggestion(
                mFakeEnvironment.elapsedRealtimeMillis(), Arrays.asList(zoneIds));
    }

    static class FakeEnvironment implements TimeZoneDetectorStrategyImpl.Environment {

        private final TestState<String> mTimeZoneId = new TestState<>();
        private final TestState<Integer> mTimeZoneConfidence = new TestState<>();
        private final List<Runnable> mAsyncRunnables = new ArrayList<>();
        private @ElapsedRealtimeLong long mElapsedRealtimeMillis;

        FakeEnvironment() {
            // Ensure the fake environment starts with the defaults a fresh device would.
            initializeTimeZoneSetting("", TIME_ZONE_CONFIDENCE_LOW);
        }

        void initializeClock(@ElapsedRealtimeLong long elapsedRealtimeMillis) {
            mElapsedRealtimeMillis = elapsedRealtimeMillis;
        }

        void initializeTimeZoneSetting(String zoneId, @TimeZoneConfidence int timeZoneConfidence) {
            mTimeZoneId.init(zoneId);
            mTimeZoneConfidence.init(timeZoneConfidence);
        }

        void incrementClock() {
            mElapsedRealtimeMillis++;
        }

        @Override
        public String getDeviceTimeZone() {
            return mTimeZoneId.getLatest();
        }

        @Override
        public int getDeviceTimeZoneConfidence() {
            return mTimeZoneConfidence.getLatest();
        }

        @Override
        public void setDeviceTimeZoneAndConfidence(
                String zoneId, @TimeZoneConfidence int confidence, String logInfo) {
            mTimeZoneId.set(zoneId);
            mTimeZoneConfidence.set(confidence);
        }

        void assertTimeZoneNotChanged() {
            mTimeZoneId.assertHasNotBeenSet();
            mTimeZoneConfidence.assertHasNotBeenSet();
        }

        void assertTimeZoneChangedTo(String timeZoneId, @TimeZoneConfidence int confidence) {
            mTimeZoneId.assertHasBeenSet();
            mTimeZoneId.assertChangeCount(1);
            mTimeZoneId.assertLatestEquals(timeZoneId);

            mTimeZoneConfidence.assertHasBeenSet();
            mTimeZoneConfidence.assertChangeCount(1);
            mTimeZoneConfidence.assertLatestEquals(confidence);
        }

        void commitAllChanges() {
            mTimeZoneId.commitLatest();
            mTimeZoneConfidence.commitLatest();
        }

        @Override
        @ElapsedRealtimeLong
        public long elapsedRealtimeMillis() {
            return mElapsedRealtimeMillis;
        }

        @Override
        public void addDebugLogEntry(String logMsg) {
            // No-op for tests
        }

        @Override
        public void dumpDebugLog(PrintWriter printWriter) {
            // No-op for tests
        }

        @Override
        public void runAsync(Runnable runnable) {
            mAsyncRunnables.add(runnable);
        }

        public void runAsyncRunnables() {
            for (Runnable runnable : mAsyncRunnables) {
                runnable.run();
            }
            mAsyncRunnables.clear();
        }
    }

    private void assertStateChangeNotificationsSent(
            TestStateChangeListener stateChangeListener, int expectedCount) {
        // The fake environment needs to be told to run posted work.
        mFakeEnvironment.runAsyncRunnables();

        stateChangeListener.assertNotificationsReceivedAndReset(expectedCount);
    }

    /**
     * A "fluent" class allows reuse of code in tests: initialization, simulation and verification
     * logic.
     */
    private class Script {

        Script initializeTimeZoneSetting(
                String zoneId, @TimeZoneConfidence int timeZoneConfidence) {
            mFakeEnvironment.initializeTimeZoneSetting(zoneId, timeZoneConfidence);
            return this;
        }

        Script initializeClock(long elapsedRealtimeMillis) {
            mFakeEnvironment.initializeClock(elapsedRealtimeMillis);
            return this;
        }

        Script registerStateChangeListener(StateChangeListener stateChangeListener) {
            mTimeZoneDetectorStrategy.addChangeListener(stateChangeListener);
            return this;
        }

        Script simulateIncrementClock() {
            mFakeEnvironment.incrementClock();
            return this;
        }

        /**
         * Simulates the user / user's configuration changing.
         */
        Script simulateConfigurationInternalChange(ConfigurationInternal configurationInternal) {
            mFakeServiceConfigAccessorSpy.simulateCurrentUserConfigurationInternalChange(
                    configurationInternal);
            return this;
        }

        /**
         * Simulates automatic time zone detection being set to the specified value.
         */
        Script simulateSetAutoMode(boolean autoDetectionEnabled) {
            ConfigurationInternal newConfig = new ConfigurationInternal.Builder(
                    mFakeServiceConfigAccessorSpy.getCurrentUserConfigurationInternal())
                    .setAutoDetectionEnabledSetting(autoDetectionEnabled)
                    .build();
            simulateConfigurationInternalChange(newConfig);
            return this;
        }

        /**
         * Simulates automatic geolocation time zone detection being set to the specified value.
         */
        Script simulateSetGeoDetectionEnabled(boolean geoDetectionEnabled) {
            ConfigurationInternal newConfig = new ConfigurationInternal.Builder(
                    mFakeServiceConfigAccessorSpy.getCurrentUserConfigurationInternal())
                    .setGeoDetectionEnabledSetting(geoDetectionEnabled)
                    .build();
            simulateConfigurationInternalChange(newConfig);
            return this;
        }

        /**
         * Simulates the time zone detection strategy receiving a location algorithm event.
         */
        Script simulateLocationAlgorithmEvent(LocationAlgorithmEvent event) {
            mTimeZoneDetectorStrategy.handleLocationAlgorithmEvent(event);
            return this;
        }

        /** Simulates the time zone detection strategy receiving a user-originated suggestion. */
        Script simulateManualTimeZoneSuggestion(
                @UserIdInt int userId, ManualTimeZoneSuggestion manualTimeZoneSuggestion,
                boolean bypassUserPolicyChecks, boolean expectedResult) {
            boolean actualResult = mTimeZoneDetectorStrategy.suggestManualTimeZone(
                    userId, manualTimeZoneSuggestion, bypassUserPolicyChecks);
            assertEquals(expectedResult, actualResult);
            return this;
        }

        /**
         * Simulates the time zone detection strategy receiving a telephony-originated suggestion.
         */
        Script simulateTelephonyTimeZoneSuggestion(TelephonyTimeZoneSuggestion timeZoneSuggestion) {
            mTimeZoneDetectorStrategy.suggestTelephonyTimeZone(timeZoneSuggestion);
            return this;
        }

        /**
         * Simulates the time zone detection strategy receiving a signal that allows it to do
         * telephony fallback.
         */
        Script simulateEnableTelephonyFallback() {
            mTimeZoneDetectorStrategy.enableTelephonyTimeZoneFallback(
                    "simulateEnableTelephonyFallback()");
            return this;
        }

        /**
         * Confirms that the device's time zone has not been set by previous actions since the test
         * state was last reset.
         */
        Script verifyTimeZoneNotChanged() {
            mFakeEnvironment.assertTimeZoneNotChanged();
            return this;
        }

        /** Verifies the device's time zone has been set and clears change tracking history. */
        Script verifyTimeZoneChangedAndReset(String zoneId, @TimeZoneConfidence int confidence) {
            mFakeEnvironment.assertTimeZoneChangedTo(zoneId, confidence);
            mFakeEnvironment.commitAllChanges();
            return this;
        }

        Script verifyTimeZoneChangedAndReset(ManualTimeZoneSuggestion suggestion) {
            verifyTimeZoneChangedAndReset(suggestion.getZoneId(), TIME_ZONE_CONFIDENCE_HIGH);
            return this;
        }

        Script verifyTimeZoneChangedAndReset(TelephonyTimeZoneSuggestion suggestion) {
            verifyTimeZoneChangedAndReset(suggestion.getZoneId(), TIME_ZONE_CONFIDENCE_HIGH);
            return this;
        }

        Script verifyTimeZoneChangedAndReset(LocationAlgorithmEvent event) {
            GeolocationTimeZoneSuggestion suggestion = event.getSuggestion();
            assertNotNull("Only events with suggestions can change the time zone", suggestion);
            assertEquals("Only use this method with unambiguous geo suggestions",
                    1, suggestion.getZoneIds().size());
            verifyTimeZoneChangedAndReset(
                    suggestion.getZoneIds().get(0), TIME_ZONE_CONFIDENCE_HIGH);
            return this;
        }

        /** Verifies the state for telephony fallback. */
        Script verifyTelephonyFallbackIsEnabled(boolean expectedEnabled) {
            assertEquals(expectedEnabled,
                    mTimeZoneDetectorStrategy.isTelephonyFallbackEnabledForTests());
            return this;
        }

        Script verifyCachedDetectorStatus(TimeZoneDetectorStatus expectedStatus) {
            assertEquals(expectedStatus,
                    mTimeZoneDetectorStrategy.getCachedDetectorStatusForTests());
            return this;
        }

        Script verifyLatestLocationAlgorithmEventReceived(LocationAlgorithmEvent expectedEvent) {
            assertEquals(expectedEvent,
                    mTimeZoneDetectorStrategy.getLatestLocationAlgorithmEvent());
            return this;
        }

        Script verifyLatestTelephonySuggestionReceived(int slotIndex,
                TelephonyTimeZoneSuggestion expectedSuggestion) {
            assertEquals(expectedSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(slotIndex).suggestion);
            return this;
        }

        Script verifyLatestQualifiedTelephonySuggestionReceived(int slotIndex,
                QualifiedTelephonyTimeZoneSuggestion expectedQualifiedSuggestion) {
            assertEquals(expectedQualifiedSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(slotIndex));
            return this;
        }

        Script resetConfigurationTracking() {
            mFakeEnvironment.commitAllChanges();
            return this;
        }
    }

    private static class TelephonyTestCase {
        public final int matchType;
        public final int quality;
        public final int expectedScore;

        TelephonyTestCase(int matchType, int quality, int expectedScore) {
            this.matchType = matchType;
            this.quality = quality;
            this.expectedScore = expectedScore;
        }

        private TelephonyTimeZoneSuggestion createSuggestion(int slotIndex, String zoneId) {
            return new TelephonyTimeZoneSuggestion.Builder(slotIndex)
                    .setZoneId(zoneId)
                    .setMatchType(matchType)
                    .setQuality(quality)
                    .build();
        }
    }

    private static TelephonyTestCase newTelephonyTestCase(
            @MatchType int matchType, @Quality int quality, int expectedScore) {
        return new TelephonyTestCase(matchType, quality, expectedScore);
    }

    private static class TestStateChangeListener implements StateChangeListener {

        private int mNotificationsReceived;

        @Override
        public void onChange() {
            mNotificationsReceived++;
        }

        public void assertNotificationsReceivedAndReset(int expectedCount) {
            assertNotificationsReceived(expectedCount);
            resetNotificationsReceivedCount();
        }

        private void resetNotificationsReceivedCount() {
            mNotificationsReceived = 0;
        }

        private void assertNotificationsReceived(int expectedCount) {
            assertEquals(expectedCount, mNotificationsReceived);
        }
    }
}
