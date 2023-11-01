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
public class TelephonyTimeZoneAlgorithmStatusTest {

    @Test
    public void testEquals() {
        TelephonyTimeZoneAlgorithmStatus one = new TelephonyTimeZoneAlgorithmStatus(
                DETECTION_ALGORITHM_STATUS_RUNNING);
        assertEqualsAndHashCode(one, one);

        {
            TelephonyTimeZoneAlgorithmStatus two = new TelephonyTimeZoneAlgorithmStatus(
                    DETECTION_ALGORITHM_STATUS_RUNNING);
            assertEqualsAndHashCode(one, two);
        }

        {
            TelephonyTimeZoneAlgorithmStatus three = new TelephonyTimeZoneAlgorithmStatus(
                    DETECTION_ALGORITHM_STATUS_NOT_RUNNING);
            assertNotEquals(one, three);
            assertNotEquals(three, one);
        }
    }

    @Test
    public void testParcelable() {
        // Algorithm running.
        {
            TelephonyTimeZoneAlgorithmStatus locationAlgorithmStatus =
                    new TelephonyTimeZoneAlgorithmStatus(
                            DETECTION_ALGORITHM_STATUS_RUNNING);
            assertRoundTripParcelable(locationAlgorithmStatus);
        }

        // Algorithm not running.
        {
            TelephonyTimeZoneAlgorithmStatus locationAlgorithmStatus =
                    new TelephonyTimeZoneAlgorithmStatus(
                            DETECTION_ALGORITHM_STATUS_NOT_RUNNING);
            assertRoundTripParcelable(locationAlgorithmStatus);
        }
    }
}
