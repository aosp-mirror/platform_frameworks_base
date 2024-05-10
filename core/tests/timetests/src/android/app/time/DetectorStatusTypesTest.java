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

import static android.app.time.DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_RUNNING;
import static android.app.time.DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_UNKNOWN;
import static android.app.time.DetectorStatusTypes.DETECTOR_STATUS_RUNNING;
import static android.app.time.DetectorStatusTypes.DETECTOR_STATUS_UNKNOWN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.app.time.DetectorStatusTypes.DetectionAlgorithmStatus;
import android.app.time.DetectorStatusTypes.DetectorStatus;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class DetectorStatusTypesTest {

    @Test
    public void testRequireValidDetectionAlgorithmStatus() {
        for (@DetectionAlgorithmStatus int status = DETECTION_ALGORITHM_STATUS_UNKNOWN;
                status <= DETECTION_ALGORITHM_STATUS_RUNNING; status++) {
            assertEquals(status, DetectorStatusTypes.requireValidDetectionAlgorithmStatus(status));
        }

        assertThrows(IllegalArgumentException.class,
                () -> DetectorStatusTypes.requireValidDetectionAlgorithmStatus(
                        DETECTION_ALGORITHM_STATUS_UNKNOWN - 1));
        assertThrows(IllegalArgumentException.class,
                () -> DetectorStatusTypes.requireValidDetectionAlgorithmStatus(
                        DETECTION_ALGORITHM_STATUS_RUNNING + 1));
    }

    @Test
    public void testFormatAndParseDetectionAlgorithmStatus() {
        for (@DetectionAlgorithmStatus int status = DETECTION_ALGORITHM_STATUS_UNKNOWN;
                status <= DETECTION_ALGORITHM_STATUS_RUNNING; status++) {
            assertEquals(status, DetectorStatusTypes.detectionAlgorithmStatusFromString(
                    DetectorStatusTypes.detectionAlgorithmStatusToString(status)));
        }

        assertThrows(IllegalArgumentException.class,
                () -> DetectorStatusTypes.detectorStatusToString(
                        DETECTION_ALGORITHM_STATUS_UNKNOWN - 1));
        assertThrows(IllegalArgumentException.class,
                () -> DetectorStatusTypes.detectorStatusToString(
                        DETECTION_ALGORITHM_STATUS_RUNNING + 1));
        assertThrows(IllegalArgumentException.class,
                () -> DetectorStatusTypes.detectorStatusFromString(null));
        assertThrows(IllegalArgumentException.class,
                () -> DetectorStatusTypes.detectorStatusFromString(""));
        assertThrows(IllegalArgumentException.class,
                () -> DetectorStatusTypes.detectorStatusFromString("FOO"));
    }

    @Test
    public void testRequireValidDetectorStatus() {
        for (@DetectorStatus int status = DETECTOR_STATUS_UNKNOWN;
                status <= DETECTOR_STATUS_RUNNING; status++) {
            assertEquals(status, DetectorStatusTypes.requireValidDetectorStatus(status));
        }

        assertThrows(IllegalArgumentException.class,
                () -> DetectorStatusTypes.requireValidDetectorStatus(DETECTOR_STATUS_UNKNOWN - 1));
        assertThrows(IllegalArgumentException.class,
                () -> DetectorStatusTypes.requireValidDetectorStatus(DETECTOR_STATUS_RUNNING + 1));
    }

    @Test
    public void testFormatAndParseDetectorStatus() {
        for (@DetectorStatus int status = DETECTOR_STATUS_UNKNOWN;
                status <= DETECTOR_STATUS_RUNNING; status++) {
            assertEquals(status, DetectorStatusTypes.detectorStatusFromString(
                    DetectorStatusTypes.detectorStatusToString(status)));
        }

        assertThrows(IllegalArgumentException.class,
                () -> DetectorStatusTypes.detectorStatusToString(DETECTOR_STATUS_UNKNOWN - 1));
        assertThrows(IllegalArgumentException.class,
                () -> DetectorStatusTypes.detectorStatusToString(DETECTOR_STATUS_RUNNING + 1));
        assertThrows(IllegalArgumentException.class,
                () -> DetectorStatusTypes.detectorStatusFromString(null));
        assertThrows(IllegalArgumentException.class,
                () -> DetectorStatusTypes.detectorStatusFromString(""));
        assertThrows(IllegalArgumentException.class,
                () -> DetectorStatusTypes.detectorStatusFromString("FOO"));
    }
}
