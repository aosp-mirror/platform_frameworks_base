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

package android.hardware.face;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A stage that may occur during face enrollment.
 *
 * @hide
 */
@Retention(RetentionPolicy.SOURCE)
@IntDef({
    FaceEnrollStage.UNKNOWN,
    FaceEnrollStage.FIRST_FRAME_RECEIVED,
    FaceEnrollStage.WAITING_FOR_CENTERING,
    FaceEnrollStage.HOLD_STILL_IN_CENTER,
    FaceEnrollStage.ENROLLING_MOVEMENT_1,
    FaceEnrollStage.ENROLLING_MOVEMENT_2,
    FaceEnrollStage.ENROLLMENT_FINISHED
})
public @interface FaceEnrollStage {
    /**
     * The current enrollment stage is not known.
     */
    int UNKNOWN = -1;

    /**
     * Enrollment has just begun. No action is needed from the user yet.
     */
    int FIRST_FRAME_RECEIVED = 0;

    /**
     * The user must center their face in the frame.
     */
    int WAITING_FOR_CENTERING = 1;

    /**
     * The user must keep their face centered in the frame.
     */
    int HOLD_STILL_IN_CENTER = 2;

    /**
     * The user must follow a first set of movement instructions.
     */
    int ENROLLING_MOVEMENT_1 = 3;

    /**
     * The user must follow a second set of movement instructions.
     */
    int ENROLLING_MOVEMENT_2 = 4;

    /**
     * Enrollment has completed. No more action is needed from the user.
     */
    int ENROLLMENT_FINISHED = 5;
}
