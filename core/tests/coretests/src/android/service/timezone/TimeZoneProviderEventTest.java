/*
 * Copyright 2021 The Android Open Source Project
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

package android.service.timezone;

import static android.app.time.ParcelableTestSupport.assertRoundTripParcelable;
import static android.service.timezone.TimeZoneProviderEvent.EVENT_TYPE_PERMANENT_FAILURE;
import static android.service.timezone.TimeZoneProviderEvent.EVENT_TYPE_SUGGESTION;
import static android.service.timezone.TimeZoneProviderEvent.EVENT_TYPE_UNCERTAIN;
import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_OK;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_FAILED;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_OK;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class TimeZoneProviderEventTest {

    public static final TimeZoneProviderStatus ARBITRARY_TIME_ZONE_PROVIDER_STATUS =
            new TimeZoneProviderStatus.Builder()
                    .setConnectivityDependencyStatus(DEPENDENCY_STATUS_OK)
                    .setLocationDetectionDependencyStatus(DEPENDENCY_STATUS_OK)
                    .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_OK)
                    .build();

    @Test
    public void createPermanentFailure() {
        long creationElapsedMillis = 1111L;
        String cause = "Cause";
        TimeZoneProviderEvent event = TimeZoneProviderEvent.createPermanentFailureEvent(
                creationElapsedMillis, cause);

        assertEquals(EVENT_TYPE_PERMANENT_FAILURE, event.getType());
        assertEquals(cause, event.getFailureCause());
        assertEquals(creationElapsedMillis, event.getCreationElapsedMillis());
        assertNull(event.getSuggestion());
        assertNull(event.getTimeZoneProviderStatus());
    }

    @Test
    public void createSuggestion() {
        long creationElapsedMillis = 1111L;
        TimeZoneProviderSuggestion suggestion = new TimeZoneProviderSuggestion.Builder()
                .setElapsedRealtimeMillis(2222L)
                .setTimeZoneIds(Collections.singletonList("Europe/London"))
                .build();

        TimeZoneProviderStatus reportedStatus = new TimeZoneProviderStatus.Builder()
                .setConnectivityDependencyStatus(DEPENDENCY_STATUS_OK)
                .setLocationDetectionDependencyStatus(DEPENDENCY_STATUS_OK)
                .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_OK)
                .build();

        assertThrows(NullPointerException.class, () -> TimeZoneProviderEvent.createSuggestionEvent(
                creationElapsedMillis, /*suggestion=*/null, reportedStatus));

        // Only TimeZoneProvider can report itself certain.
        {
            TimeZoneProviderEvent event = TimeZoneProviderEvent.createSuggestionEvent(
                    creationElapsedMillis, suggestion, reportedStatus);
            assertEquals(EVENT_TYPE_SUGGESTION, event.getType());
            assertEquals(creationElapsedMillis, event.getCreationElapsedMillis());
            assertNull(event.getFailureCause());
            assertEquals(suggestion, event.getSuggestion());
        }

        // Legacy API events can be created where the TimeZoneProviderStatus is omitted.
        {
            TimeZoneProviderStatus legacyStatus = null;
            TimeZoneProviderEvent legacyEvent = TimeZoneProviderEvent.createSuggestionEvent(
                    creationElapsedMillis, suggestion, legacyStatus);
            assertEquals(legacyStatus, legacyEvent.getTimeZoneProviderStatus());
        }
    }

    @Test
    public void createUncertain() {
        long creationElapsedMillis = 1111L;

        // The TimeZoneProvider can report itself uncertain.
        {
            TimeZoneProviderEvent event = TimeZoneProviderEvent.createUncertainEvent(
                    creationElapsedMillis, ARBITRARY_TIME_ZONE_PROVIDER_STATUS);
            assertEquals(EVENT_TYPE_UNCERTAIN, event.getType());
            assertEquals(creationElapsedMillis, event.getCreationElapsedMillis());
            assertNull(event.getFailureCause());
            assertNull(event.getSuggestion());
        }

        // Legacy API events can be created where the TimeZoneProviderStatus is omitted.
        {
            TimeZoneProviderStatus legacyStatus = null;
            TimeZoneProviderEvent legacyEvent = TimeZoneProviderEvent.createUncertainEvent(
                    creationElapsedMillis, legacyStatus);
            assertEquals(legacyStatus, legacyEvent.getTimeZoneProviderStatus());
        }
    }

    @Test
    public void isEquivalentToAndEquals() {
        long creationElapsedMillis = 1111L;
        TimeZoneProviderEvent failEvent =
                TimeZoneProviderEvent.createPermanentFailureEvent(creationElapsedMillis, "one");
        TimeZoneProviderStatus providerStatus = ARBITRARY_TIME_ZONE_PROVIDER_STATUS;

        TimeZoneProviderEvent uncertainEvent =
                TimeZoneProviderEvent.createUncertainEvent(creationElapsedMillis, providerStatus);
        TimeZoneProviderSuggestion suggestion = new TimeZoneProviderSuggestion.Builder()
                .setElapsedRealtimeMillis(creationElapsedMillis)
                .setTimeZoneIds(Collections.singletonList("Europe/London"))
                .build();
        TimeZoneProviderEvent suggestionEvent = TimeZoneProviderEvent.createSuggestionEvent(
                creationElapsedMillis, suggestion, providerStatus);

        assertNotEquals(failEvent, uncertainEvent);
        assertNotEquivalentTo(failEvent, uncertainEvent);

        assertNotEquals(failEvent, suggestionEvent);
        assertNotEquivalentTo(failEvent, suggestionEvent);

        assertNotEquals(uncertainEvent, suggestionEvent);
        assertNotEquivalentTo(uncertainEvent, suggestionEvent);
    }

    @Test
    public void isEquivalentToAndEquals_permanentFailure() {
        TimeZoneProviderEvent fail1v1 =
                TimeZoneProviderEvent.createPermanentFailureEvent(1111L, "one");
        assertEquals(fail1v1, fail1v1);
        assertIsEquivalentTo(fail1v1, fail1v1);
        assertNotEquals(fail1v1, null);
        assertNotEquivalentTo(fail1v1, null);

        {
            TimeZoneProviderEvent fail1v2 =
                    TimeZoneProviderEvent.createPermanentFailureEvent(1111L, "one");
            assertEquals(fail1v1, fail1v2);
            assertIsEquivalentTo(fail1v1, fail1v2);

            TimeZoneProviderEvent fail2 =
                    TimeZoneProviderEvent.createPermanentFailureEvent(2222L, "two");
            assertNotEquals(fail1v1, fail2);
            assertIsEquivalentTo(fail1v1, fail2);
        }
    }

    @Test
    public void isEquivalentToAndEquals_uncertain() {
        TimeZoneProviderStatus status1 = new TimeZoneProviderStatus.Builder()
                .setLocationDetectionDependencyStatus(DEPENDENCY_STATUS_OK)
                .setConnectivityDependencyStatus(DEPENDENCY_STATUS_OK)
                .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_OK)
                .build();
        TimeZoneProviderStatus status2 = new TimeZoneProviderStatus.Builder()
                .setLocationDetectionDependencyStatus(DEPENDENCY_STATUS_OK)
                .setConnectivityDependencyStatus(DEPENDENCY_STATUS_OK)
                .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_FAILED)
                .build();

        TimeZoneProviderEvent uncertain1v1 =
                TimeZoneProviderEvent.createUncertainEvent(1111L, status1);
        assertEquals(uncertain1v1, uncertain1v1);
        assertIsEquivalentTo(uncertain1v1, uncertain1v1);
        assertNotEquals(uncertain1v1, null);
        assertNotEquivalentTo(uncertain1v1, null);

        {
            TimeZoneProviderEvent uncertain1v2 =
                    TimeZoneProviderEvent.createUncertainEvent(1111L, status1);
            assertEquals(uncertain1v1, uncertain1v2);
            assertIsEquivalentTo(uncertain1v1, uncertain1v2);

            TimeZoneProviderEvent uncertain2 =
                    TimeZoneProviderEvent.createUncertainEvent(2222L, status1);
            assertNotEquals(uncertain1v1, uncertain2);
            assertIsEquivalentTo(uncertain1v1, uncertain2);

            TimeZoneProviderEvent uncertain3 =
                    TimeZoneProviderEvent.createUncertainEvent(1111L, status2);
            assertNotEquals(uncertain1v1, uncertain3);
            assertNotEquivalentTo(uncertain1v1, uncertain3);
        }
    }

    @Test
    public void isEquivalentToAndEquals_suggestion() {
        TimeZoneProviderStatus status1 = new TimeZoneProviderStatus.Builder()
                .setLocationDetectionDependencyStatus(DEPENDENCY_STATUS_OK)
                .setConnectivityDependencyStatus(DEPENDENCY_STATUS_OK)
                .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_OK)
                .build();
        TimeZoneProviderStatus status2 = new TimeZoneProviderStatus.Builder()
                .setLocationDetectionDependencyStatus(DEPENDENCY_STATUS_OK)
                .setConnectivityDependencyStatus(DEPENDENCY_STATUS_OK)
                .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_FAILED)
                .build();
        TimeZoneProviderSuggestion suggestion1 = new TimeZoneProviderSuggestion.Builder()
                .setElapsedRealtimeMillis(1111L)
                .setTimeZoneIds(Collections.singletonList("Europe/London"))
                .build();
        TimeZoneProviderEvent certain1v1 =
                TimeZoneProviderEvent.createSuggestionEvent(1111L, suggestion1, status1);
        assertEquals(certain1v1, certain1v1);
        assertIsEquivalentTo(certain1v1, certain1v1);
        assertNotEquals(certain1v1, null);
        assertNotEquivalentTo(certain1v1, null);

        {
            // Same time, suggestion, and status.
            TimeZoneProviderEvent certain1v2 =
                    TimeZoneProviderEvent.createSuggestionEvent(1111L, suggestion1, status1);
            assertEquals(certain1v1, certain1v2);
            assertIsEquivalentTo(certain1v1, certain1v2);

            // Different time, same suggestion and status.
            TimeZoneProviderEvent certain1v3 =
                    TimeZoneProviderEvent.createSuggestionEvent(2222L, suggestion1, status1);
            assertNotEquals(certain1v1, certain1v3);
            assertIsEquivalentTo(certain1v1, certain1v3);

            // suggestion1 is equivalent to suggestion2, but not equal
            TimeZoneProviderSuggestion suggestion2 = new TimeZoneProviderSuggestion.Builder()
                    .setElapsedRealtimeMillis(2222L)
                    .setTimeZoneIds(Collections.singletonList("Europe/London"))
                    .build();
            assertNotEquals(suggestion1, suggestion2);
            TimeZoneProviderSuggestionTest.assertIsEquivalentTo(suggestion1, suggestion2);
            TimeZoneProviderEvent certain2 =
                    TimeZoneProviderEvent.createSuggestionEvent(2222L, suggestion2, status1);
            assertNotEquals(certain1v1, certain2);
            assertIsEquivalentTo(certain1v1, certain2);

            // suggestion3 is not equivalent to suggestion1
            TimeZoneProviderSuggestion suggestion3 = new TimeZoneProviderSuggestion.Builder()
                    .setTimeZoneIds(Collections.singletonList("Europe/Paris"))
                    .build();
            TimeZoneProviderEvent certain3 =
                    TimeZoneProviderEvent.createSuggestionEvent(2222L, suggestion3, status1);
            assertNotEquals(certain1v1, certain3);
            assertNotEquivalentTo(certain1v1, certain3);

            TimeZoneProviderEvent certain4 =
                    TimeZoneProviderEvent.createSuggestionEvent(2222L, suggestion1, status2);
            assertNotEquals(certain1v1, certain4);
            assertNotEquivalentTo(certain1v1, certain4);
        }
    }

    @Test
    public void testParcelable_failureEvent() {
        TimeZoneProviderEvent event =
                TimeZoneProviderEvent.createPermanentFailureEvent(1111L, "failure reason");
        assertRoundTripParcelable(event);
    }

    @Test
    public void testParcelable_uncertain() {
        TimeZoneProviderEvent event = TimeZoneProviderEvent.createUncertainEvent(
                1111L, ARBITRARY_TIME_ZONE_PROVIDER_STATUS);
        assertRoundTripParcelable(event);
    }

    @Test
    public void testParcelable_uncertain_legacy() {
        TimeZoneProviderEvent event = TimeZoneProviderEvent.createUncertainEvent(1111L, null);
        assertRoundTripParcelable(event);
    }

    @Test
    public void testParcelable_suggestion() {
        TimeZoneProviderSuggestion suggestion = new TimeZoneProviderSuggestion.Builder()
                .setTimeZoneIds(Arrays.asList("Europe/London", "Europe/Paris"))
                .build();
        TimeZoneProviderEvent event = TimeZoneProviderEvent.createSuggestionEvent(
                1111L, suggestion, ARBITRARY_TIME_ZONE_PROVIDER_STATUS);
        assertRoundTripParcelable(event);
    }

    @Test
    public void testParcelable_suggestion_legacy() {
        TimeZoneProviderSuggestion suggestion = new TimeZoneProviderSuggestion.Builder()
                .setTimeZoneIds(Arrays.asList("Europe/London", "Europe/Paris"))
                .build();
        TimeZoneProviderEvent event = TimeZoneProviderEvent.createSuggestionEvent(
                1111L, suggestion, null);
        assertRoundTripParcelable(event);
    }

    private static void assertNotEquivalentTo(
            TimeZoneProviderEvent one, TimeZoneProviderEvent two) {
        if (one == null && two == null) {
            fail("null arguments");
        }
        if (one != null) {
            assertFalse("one=" + one + ", two=" + two, one.isEquivalentTo(two));
        }
        if (two != null) {
            assertFalse("one=" + one + ", two=" + two, two.isEquivalentTo(one));
        }
    }

    private static void assertIsEquivalentTo(TimeZoneProviderEvent one, TimeZoneProviderEvent two) {
        if (one == null || two == null) {
            fail("null arguments");
        }
        assertTrue("one=" + one + ", two=" + two, one.isEquivalentTo(two));
        assertTrue("one=" + one + ", two=" + two, two.isEquivalentTo(one));
    }
}
