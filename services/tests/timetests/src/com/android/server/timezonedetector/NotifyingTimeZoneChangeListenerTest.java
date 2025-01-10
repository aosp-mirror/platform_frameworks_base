/*
 * Copyright 2024 The Android Open Source Project
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

import static com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.AUTO_REVERT_THRESHOLD;
import static com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.SIGNAL_TYPE_NONE;
import static com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.SIGNAL_TYPE_UNKNOWN;
import static com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.STATUS_REJECTED;
import static com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.STATUS_SUPERSEDED;
import static com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.STATUS_UNKNOWN;
import static com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.STATUS_UNTRACKED;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.ORIGIN_LOCATION;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.ORIGIN_MANUAL;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.ORIGIN_TELEPHONY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.spy;

import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.UiAutomation;
import android.content.Context;
import android.os.HandlerThread;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.flags.Flags;
import com.android.server.timezonedetector.NotifyingTimeZoneChangeListener.TimeZoneChangeRecord;
import com.android.server.timezonedetector.TimeZoneChangeListener.TimeZoneChangeEvent;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;

/**
 * White-box unit tests for {@link NotifyingTimeZoneChangeListener}.
 */
@RunWith(JUnitParamsRunner.class)
@EnableFlags(Flags.FLAG_DATETIME_NOTIFICATIONS)
public class NotifyingTimeZoneChangeListenerTest {

    @ClassRule
    public static final SetFlagsRule.ClassRule mClassRule = new SetFlagsRule.ClassRule();

    @Rule(order = 0)
    public final SetFlagsRule mSetFlagsRule = mClassRule.createSetFlagsRule();

    @Rule(order = 1)
    public final MockitoRule mockito = MockitoJUnit.rule();

    public static List<@TimeZoneDetectorStrategy.Origin Integer> getDetectionOrigins() {
        return List.of(ORIGIN_LOCATION, ORIGIN_TELEPHONY);
    }

    private static final long ARBITRARY_ELAPSED_REALTIME_MILLIS = 1234;
    /** A time zone used for initialization that does not occur elsewhere in tests. */
    private static final String ARBITRARY_TIME_ZONE_ID = "Etc/UTC";
    private static final String INTERACT_ACROSS_USERS_FULL_PERMISSION =
            "android.permission.INTERACT_ACROSS_USERS_FULL";

    @Mock
    private Context mContext;
    private UiAutomation mUiAutomation;

    private FakeNotificationManager mNotificationManager;
    private HandlerThread mHandlerThread;
    private TestHandler mHandler;
    private FakeServiceConfigAccessor mServiceConfigAccessor;
    private int mUid;

    private NotifyingTimeZoneChangeListener mTimeZoneChangeTracker;

    @Before
    public void setUp() {
        mUid = Process.myUid();
        // Create a thread + handler for processing the work that the service posts.
        mHandlerThread = new HandlerThread("TimeZoneDetectorInternalTest");
        mHandlerThread.start();
        mHandler = new TestHandler(mHandlerThread.getLooper());

        ConfigurationInternal config = new ConfigurationInternal.Builder()
                .setUserId(mUid)
                .setTelephonyDetectionFeatureSupported(true)
                .setGeoDetectionFeatureSupported(true)
                .setTelephonyFallbackSupported(false)
                .setGeoDetectionRunInBackgroundEnabled(false)
                .setEnhancedMetricsCollectionEnabled(false)
                .setUserConfigAllowed(true)
                .setAutoDetectionEnabledSetting(false)
                .setLocationEnabledSetting(true)
                .setGeoDetectionEnabledSetting(false)
                .setNotificationsSupported(true)
                .setNotificationsTrackingSupported(true)
                .setNotificationsEnabledSetting(false)
                .setManualChangeTrackingSupported(false)
                .build();

        mServiceConfigAccessor = spy(new FakeServiceConfigAccessor());
        mServiceConfigAccessor.initializeCurrentUserConfiguration(config);

        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mUiAutomation.adoptShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL_PERMISSION);

        mNotificationManager = new FakeNotificationManager(mContext, InstantSource.system());

        mTimeZoneChangeTracker = new NotifyingTimeZoneChangeListener(mHandler, mContext,
                mServiceConfigAccessor, mNotificationManager);
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
        mHandlerThread.join();
    }

    @Test
    public void process_autoDetectionOff_noManualTracking_shouldTrackWithoutNotifying() {
        enableTimeZoneNotifications();

        TimeZoneChangeRecord expectedChangeEvent = new TimeZoneChangeRecord(
                /* id= */ 1,
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 0,
                        /* unixEpochTimeMillis= */ 1726597800000L,
                        /* origin= */ ORIGIN_MANUAL,
                        /* userId= */ mUid,
                        /* oldZoneId= */ "Europe/Paris",
                        /* newZoneId= */ "Europe/London",
                        /* newConfidence= */ 1,
                        /* cause= */ "NO_REASON"));
        expectedChangeEvent.setStatus(STATUS_UNTRACKED, SIGNAL_TYPE_NONE);

        assertNull(mTimeZoneChangeTracker.getLastTimeZoneChangeRecord());

        mTimeZoneChangeTracker.process(expectedChangeEvent.getEvent());

        assertEquals(expectedChangeEvent, mTimeZoneChangeTracker.getLastTimeZoneChangeRecord());
        assertEquals(0, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(0);
    }

    @Test
    public void process_autoDetectionOff_shouldTrackWithoutNotifying() {
        enableNotificationsWithManualChangeTracking();

        TimeZoneChangeRecord expectedChangeEvent = new TimeZoneChangeRecord(
                /* id= */ 1,
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 0,
                        /* unixEpochTimeMillis= */ 1726597800000L,
                        /* origin= */ ORIGIN_MANUAL,
                        /* userId= */ mUid,
                        /* oldZoneId= */ "Europe/Paris",
                        /* newZoneId= */ "Europe/London",
                        /* newConfidence= */ 1,
                        /* cause= */ "NO_REASON"));
        expectedChangeEvent.setStatus(STATUS_UNTRACKED, SIGNAL_TYPE_NONE);

        assertNull(mTimeZoneChangeTracker.getLastTimeZoneChangeRecord());

        mTimeZoneChangeTracker.process(expectedChangeEvent.getEvent());

        assertEquals(expectedChangeEvent, mTimeZoneChangeTracker.getLastTimeZoneChangeRecord());
        assertEquals(0, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(1);
    }

    @Test
    @Parameters(method = "getDetectionOrigins")
    public void process_automaticDetection_trackingSupported(
            @TimeZoneDetectorStrategy.Origin int origin) {
        if (origin == ORIGIN_LOCATION) {
            enableLocationTimeZoneDetection();
        } else if (origin == ORIGIN_TELEPHONY) {
            enableTelephonyTimeZoneDetection();
        } else {
            throw new IllegalStateException(
                    "The given origin has not been implemented for this test: " + origin);
        }

        enableNotificationsWithManualChangeTracking();

        TimeZoneChangeRecord expectedChangeEvent = new TimeZoneChangeRecord(
                /* id= */ 1,
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 0,
                        /* unixEpochTimeMillis= */ 1726597800000L,
                        /* origin= */ origin,
                        /* userId= */ mUid,
                        /* oldZoneId= */ "Europe/Paris",
                        /* newZoneId= */ "Europe/London",
                        /* newConfidence= */ 1,
                        /* cause= */ "NO_REASON"));
        expectedChangeEvent.setStatus(STATUS_UNKNOWN, SIGNAL_TYPE_UNKNOWN);

        assertNull(mTimeZoneChangeTracker.getLastTimeZoneChangeRecord());

        // lastTrackedChangeEvent == null
        mTimeZoneChangeTracker.process(expectedChangeEvent.getEvent());
        TimeZoneChangeRecord trackedEvent1 = mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();

        assertEquals(expectedChangeEvent, trackedEvent1);
        assertEquals(1, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(1);

        expectedChangeEvent = new TimeZoneChangeRecord(
                /* id= */ 2,
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 1000L,
                        /* unixEpochTimeMillis= */ 1726597800000L + 1000L,
                        /* origin= */ origin,
                        /* userId= */ mUid,
                        /* oldZoneId= */ "Europe/London",
                        /* newZoneId= */ "Europe/Paris",
                        /* newConfidence= */ 1,
                        /* cause= */ "NO_REASON"));
        expectedChangeEvent.setStatus(STATUS_UNKNOWN, SIGNAL_TYPE_UNKNOWN);

        // lastTrackedChangeEvent != null
        mTimeZoneChangeTracker.process(expectedChangeEvent.getEvent());
        TimeZoneChangeRecord trackedEvent2 = mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();

        assertEquals(STATUS_SUPERSEDED, trackedEvent1.getStatus());
        assertEquals(expectedChangeEvent, trackedEvent2);
        assertEquals(2, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(2);

        disableTimeZoneAutoDetection();

        // Test manual change within revert threshold
        {
            expectedChangeEvent = new TimeZoneChangeRecord(
                    /* id= */ 3,
                    new TimeZoneChangeEvent(
                            /* elapsedRealtimeMillis= */ 999L + AUTO_REVERT_THRESHOLD,
                            /* unixEpochTimeMillis= */
                            1726597800000L + 999L + AUTO_REVERT_THRESHOLD,
                            /* origin= */ ORIGIN_MANUAL,
                            /* userId= */ mUid,
                            /* oldZoneId= */ "Europe/Paris",
                            /* newZoneId= */ "Europe/London",
                            /* newConfidence= */ 1,
                            /* cause= */ "NO_REASON"));
            expectedChangeEvent.setStatus(STATUS_UNTRACKED, SIGNAL_TYPE_NONE);

            mTimeZoneChangeTracker.process(expectedChangeEvent.getEvent());
            TimeZoneChangeRecord trackedEvent3 =
                    mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();

            // The user manually changed the time zone within a short period of receiving the
            // notification, indicating that they rejected the automatic change of time zone
            assertEquals(STATUS_REJECTED, trackedEvent2.getStatus());
            assertEquals(expectedChangeEvent, trackedEvent3);
            assertEquals(2, mNotificationManager.getNotifications().size());
            mHandler.assertTotalMessagesEnqueued(3);
        }

        // Test manual change outside of revert threshold
        {
            // [START] Reset previous event
            enableNotificationsWithManualChangeTracking();
            mTimeZoneChangeTracker.process(trackedEvent2.getEvent());
            trackedEvent2 = mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();
            disableTimeZoneAutoDetection();
            // [END] Reset previous event

            expectedChangeEvent = new TimeZoneChangeRecord(
                    /* id= */ 5,
                    new TimeZoneChangeEvent(
                            /* elapsedRealtimeMillis= */ 1001L + AUTO_REVERT_THRESHOLD,
                            /* unixEpochTimeMillis= */
                            1726597800000L + 1001L + AUTO_REVERT_THRESHOLD,
                            /* origin= */ ORIGIN_MANUAL,
                            /* userId= */ mUid,
                            /* oldZoneId= */ "Europe/Paris",
                            /* newZoneId= */ "Europe/London",
                            /* newConfidence= */ 1,
                            /* cause= */ "NO_REASON"));
            expectedChangeEvent.setStatus(STATUS_UNTRACKED, SIGNAL_TYPE_NONE);

            mTimeZoneChangeTracker.process(expectedChangeEvent.getEvent());
            TimeZoneChangeRecord trackedEvent3 =
                    mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();

            // The user manually changed the time zone outside of the period we consider as a revert
            assertEquals(STATUS_SUPERSEDED, trackedEvent2.getStatus());
            assertEquals(expectedChangeEvent, trackedEvent3);
            assertEquals(3, mNotificationManager.getNotifications().size());
            mHandler.assertTotalMessagesEnqueued(5);
        }
    }

    @Test
    @Parameters(method = "getDetectionOrigins")
    public void process_automaticDetection_trackingSupported_missingTransition(
            @TimeZoneDetectorStrategy.Origin int origin) {
        if (origin == ORIGIN_LOCATION) {
            enableLocationTimeZoneDetection();
        } else if (origin == ORIGIN_TELEPHONY) {
            enableTelephonyTimeZoneDetection();
        } else {
            throw new IllegalStateException(
                    "The given origin has not been implemented for this test: " + origin);
        }

        enableNotificationsWithManualChangeTracking();

        TimeZoneChangeRecord expectedChangeEvent = new TimeZoneChangeRecord(
                /* id= */ 1,
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 0,
                        /* unixEpochTimeMillis= */ 1726597800000L,
                        /* origin= */ origin,
                        /* userId= */ mUid,
                        /* oldZoneId= */ "Europe/Paris",
                        /* newZoneId= */ "Europe/London",
                        /* newConfidence= */ 1,
                        /* cause= */ "NO_REASON"));
        expectedChangeEvent.setStatus(STATUS_UNKNOWN, SIGNAL_TYPE_UNKNOWN);

        assertNull(mTimeZoneChangeTracker.getLastTimeZoneChangeRecord());

        // lastTrackedChangeEvent == null
        mTimeZoneChangeTracker.process(expectedChangeEvent.getEvent());
        TimeZoneChangeRecord trackedEvent1 = mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();

        assertEquals(expectedChangeEvent, trackedEvent1);
        assertEquals(1, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(1);

        expectedChangeEvent = new TimeZoneChangeRecord(
                /* id= */ 3,
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 1000L,
                        /* unixEpochTimeMillis= */ 1726597800000L + 1000L,
                        /* origin= */ origin,
                        /* userId= */ mUid,
                        /* oldZoneId= */ "Europe/Athens",
                        /* newZoneId= */ "Europe/Paris",
                        /* newConfidence= */ 1,
                        /* cause= */ "NO_REASON"));
        expectedChangeEvent.setStatus(STATUS_UNKNOWN, SIGNAL_TYPE_UNKNOWN);

        // lastTrackedChangeEvent != null
        mTimeZoneChangeTracker.process(expectedChangeEvent.getEvent());
        TimeZoneChangeRecord trackedEvent2 = mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();

        assertEquals(STATUS_SUPERSEDED, trackedEvent1.getStatus());
        assertEquals(expectedChangeEvent, trackedEvent2);
        assertEquals(2, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(2);
    }

    @Test
    @Parameters(method = "getDetectionOrigins")
    public void process_automaticDetection_trackingSupported_sameOffset(
            @TimeZoneDetectorStrategy.Origin int origin) {
        if (origin == ORIGIN_LOCATION) {
            enableLocationTimeZoneDetection();
        } else if (origin == ORIGIN_TELEPHONY) {
            enableTelephonyTimeZoneDetection();
        } else {
            throw new IllegalStateException(
                    "The given origin has not been implemented for this test: " + origin);
        }

        enableNotificationsWithManualChangeTracking();

        TimeZoneChangeRecord expectedChangeEvent = new TimeZoneChangeRecord(
                /* id= */ 1,
                new TimeZoneChangeEvent(
                        /* elapsedRealtimeMillis= */ 0,
                        /* unixEpochTimeMillis= */ 1726597800000L,
                        /* origin= */ origin,
                        /* userId= */ mUid,
                        /* oldZoneId= */ "Europe/Paris",
                        /* newZoneId= */ "Europe/Rome",
                        /* newConfidence= */ 1,
                        /* cause= */ "NO_REASON"));
        expectedChangeEvent.setStatus(STATUS_UNKNOWN, SIGNAL_TYPE_UNKNOWN);

        assertNull(mTimeZoneChangeTracker.getLastTimeZoneChangeRecord());

        // lastTrackedChangeEvent == null
        mTimeZoneChangeTracker.process(expectedChangeEvent.getEvent());
        TimeZoneChangeRecord trackedEvent1 = mTimeZoneChangeTracker.getLastTimeZoneChangeRecord();

        assertEquals(expectedChangeEvent, trackedEvent1);
        // No notification sent for the same UTC offset
        assertEquals(0, mNotificationManager.getNotifications().size());
        mHandler.assertTotalMessagesEnqueued(1);
    }

    private void enableLocationTimeZoneDetection() {
        ConfigurationInternal oldConfiguration =
                mServiceConfigAccessor.getCurrentUserConfigurationInternal();
        ConfigurationInternal newConfiguration = toBuilder(oldConfiguration)
                .setAutoDetectionEnabledSetting(true)
                .setGeoDetectionFeatureSupported(true)
                .setGeoDetectionEnabledSetting(true)
                .build();

        mServiceConfigAccessor.simulateCurrentUserConfigurationInternalChange(newConfiguration);
    }

    private void enableTelephonyTimeZoneDetection() {
        ConfigurationInternal oldConfiguration =
                mServiceConfigAccessor.getCurrentUserConfigurationInternal();
        ConfigurationInternal newConfiguration = toBuilder(oldConfiguration)
                .setAutoDetectionEnabledSetting(true)
                .setGeoDetectionEnabledSetting(false)
                .setTelephonyDetectionFeatureSupported(true)
                .setTelephonyFallbackSupported(true)
                .build();

        mServiceConfigAccessor.simulateCurrentUserConfigurationInternalChange(newConfiguration);
    }

    private void enableTimeZoneNotifications() {
        ConfigurationInternal oldConfiguration =
                mServiceConfigAccessor.getCurrentUserConfigurationInternal();
        ConfigurationInternal newConfiguration = toBuilder(oldConfiguration)
                .setNotificationsSupported(true)
                .setNotificationsTrackingSupported(true)
                .setNotificationsEnabledSetting(true)
                .setManualChangeTrackingSupported(false)
                .build();

        mServiceConfigAccessor.simulateCurrentUserConfigurationInternalChange(newConfiguration);
    }

    private void enableNotificationsWithManualChangeTracking() {
        ConfigurationInternal oldConfiguration =
                mServiceConfigAccessor.getCurrentUserConfigurationInternal();
        ConfigurationInternal newConfiguration = toBuilder(oldConfiguration)
                .setNotificationsSupported(true)
                .setNotificationsTrackingSupported(true)
                .setNotificationsEnabledSetting(true)
                .setManualChangeTrackingSupported(true)
                .build();

        mServiceConfigAccessor.simulateCurrentUserConfigurationInternalChange(newConfiguration);
    }

    private void disableTimeZoneAutoDetection() {
        ConfigurationInternal oldConfiguration =
                mServiceConfigAccessor.getCurrentUserConfigurationInternal();
        ConfigurationInternal newConfiguration = toBuilder(oldConfiguration)
                .setAutoDetectionEnabledSetting(false)
                .setGeoDetectionEnabledSetting(false)
                .build();

        mServiceConfigAccessor.simulateCurrentUserConfigurationInternalChange(newConfiguration);
    }

    private ConfigurationInternal.Builder toBuilder(ConfigurationInternal config) {
        return new ConfigurationInternal.Builder()
                .setUserId(config.getUserId())
                .setTelephonyDetectionFeatureSupported(config.isTelephonyDetectionSupported())
                .setGeoDetectionFeatureSupported(config.isGeoDetectionSupported())
                .setTelephonyFallbackSupported(config.isTelephonyFallbackSupported())
                .setGeoDetectionRunInBackgroundEnabled(
                        config.getGeoDetectionRunInBackgroundEnabledSetting())
                .setEnhancedMetricsCollectionEnabled(config.isEnhancedMetricsCollectionEnabled())
                .setUserConfigAllowed(config.isUserConfigAllowed())
                .setAutoDetectionEnabledSetting(config.getAutoDetectionEnabledSetting())
                .setLocationEnabledSetting(config.getLocationEnabledSetting())
                .setGeoDetectionEnabledSetting(config.getGeoDetectionEnabledSetting())
                .setNotificationsTrackingSupported(config.isNotificationTrackingSupported())
                .setNotificationsEnabledSetting(config.getNotificationsEnabledBehavior())
                .setNotificationsSupported(config.areNotificationsSupported())
                .setManualChangeTrackingSupported(config.isManualChangeTrackingSupported());
    }

    private static class FakeNotificationManager extends NotificationManager {

        private final List<Notification> mNotifications = new ArrayList<>();

        FakeNotificationManager(Context context, InstantSource clock) {
            super(context, clock);
        }

        @Override
        public void notifyAsUser(@Nullable String tag, int id, Notification notification,
                UserHandle user) {
            mNotifications.add(notification);
        }

        public List<Notification> getNotifications() {
            return mNotifications;
        }
    }
}
