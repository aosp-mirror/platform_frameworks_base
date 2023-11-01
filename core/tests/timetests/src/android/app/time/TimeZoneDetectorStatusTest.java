/*
 * Copyright 2022 The Android Open Source Project
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

package android.app.time;

import static android.app.time.DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_NOT_RUNNING;
import static android.app.time.DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_RUNNING;
import static android.app.time.DetectorStatusTypes.DETECTOR_STATUS_NOT_RUNNING;
import static android.app.time.DetectorStatusTypes.DETECTOR_STATUS_RUNNING;
import static android.app.time.ParcelableTestSupport.assertEqualsAndHashCode;
import static android.app.time.ParcelableTestSupport.assertRoundTripParcelable;

import static org.junit.Assert.assertNotEquals;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class TimeZoneDetectorStatusTest {

    private static final TelephonyTimeZoneAlgorithmStatus ARBITRARY_TELEPHONY_ALGORITHM_STATUS =
            new TelephonyTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING);

    private static final LocationTimeZoneAlgorithmStatus ARBITRARY_LOCATION_ALGORITHM_STATUS =
            new LocationTimeZoneAlgorithmStatus(
                    DETECTION_ALGORITHM_STATUS_RUNNING,
                    LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_NOT_READY, null,
                    LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_NOT_PRESENT, null);

    @Test
    public void testEquals() {
        TimeZoneDetectorStatus one = new TimeZoneDetectorStatus(DETECTOR_STATUS_RUNNING,
                ARBITRARY_TELEPHONY_ALGORITHM_STATUS, ARBITRARY_LOCATION_ALGORITHM_STATUS);
        assertEqualsAndHashCode(one, one);

        {
            TimeZoneDetectorStatus two = new TimeZoneDetectorStatus(DETECTOR_STATUS_RUNNING,
                    ARBITRARY_TELEPHONY_ALGORITHM_STATUS, ARBITRARY_LOCATION_ALGORITHM_STATUS);
            assertEqualsAndHashCode(one, two);
        }

        {
            TimeZoneDetectorStatus three = new TimeZoneDetectorStatus(DETECTOR_STATUS_NOT_RUNNING,
                    ARBITRARY_TELEPHONY_ALGORITHM_STATUS, ARBITRARY_LOCATION_ALGORITHM_STATUS);
            assertNotEquals(one, three);
            assertNotEquals(three, one);
        }

        {
            TelephonyTimeZoneAlgorithmStatus telephonyAlgorithmStatus =
                    new TelephonyTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_NOT_RUNNING);
            assertNotEquals(telephonyAlgorithmStatus, ARBITRARY_TELEPHONY_ALGORITHM_STATUS);

            TimeZoneDetectorStatus three = new TimeZoneDetectorStatus(DETECTOR_STATUS_NOT_RUNNING,
                    telephonyAlgorithmStatus, ARBITRARY_LOCATION_ALGORITHM_STATUS);
            assertNotEquals(one, three);
            assertNotEquals(three, one);
        }

        {
            LocationTimeZoneAlgorithmStatus locationAlgorithmStatus =
                    new LocationTimeZoneAlgorithmStatus(
                            DETECTION_ALGORITHM_STATUS_NOT_RUNNING,
                            LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_NOT_READY, null,
                            LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_NOT_READY, null);
            assertNotEquals(locationAlgorithmStatus, ARBITRARY_LOCATION_ALGORITHM_STATUS);

            TimeZoneDetectorStatus three = new TimeZoneDetectorStatus(DETECTOR_STATUS_NOT_RUNNING,
                    ARBITRARY_TELEPHONY_ALGORITHM_STATUS, locationAlgorithmStatus);
            assertNotEquals(one, three);
            assertNotEquals(three, one);
        }
    }

    @Test
    public void testParcelable() {
        // Detector running.
        {
            TimeZoneDetectorStatus locationAlgorithmStatus = new TimeZoneDetectorStatus(
                    DETECTOR_STATUS_RUNNING, ARBITRARY_TELEPHONY_ALGORITHM_STATUS,
                    ARBITRARY_LOCATION_ALGORITHM_STATUS);
            assertRoundTripParcelable(locationAlgorithmStatus);
        }

        // Detector not running.
        {
            TimeZoneDetectorStatus locationAlgorithmStatus =
                    new TimeZoneDetectorStatus(DETECTOR_STATUS_NOT_RUNNING,
                            ARBITRARY_TELEPHONY_ALGORITHM_STATUS,
                            ARBITRARY_LOCATION_ALGORITHM_STATUS);
            assertRoundTripParcelable(locationAlgorithmStatus);
        }
    }
}
