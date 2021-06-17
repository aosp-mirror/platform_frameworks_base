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
 * A collection of constants representing different stages of face enrollment.
 *
 * @hide
 */
public final class FaceEnrollStages {
    // Prevent instantiation.
    private FaceEnrollStages() {}

    /**
     * A stage that may occur during face enrollment.
     *
     * @hide
     */
    @IntDef({
        UNKNOWN,
        FIRST_FRAME_RECEIVED,
        WAITING_FOR_CENTERING,
        HOLD_STILL_IN_CENTER,
        ENROLLING_MOVEMENT_1,
        ENROLLING_MOVEMENT_2,
        ENROLLMENT_FINISHED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FaceEnrollStage {}

    /**
     * The current enrollment stage is not known.
     */
    public static final int UNKNOWN = 0;

    /**
     * Enrollment has just begun. No action is needed from the user yet.
     */
    public static final int FIRST_FRAME_RECEIVED = 1;

    /**
     * The user must center their face in the frame.
     */
    public static final int WAITING_FOR_CENTERING = 2;

    /**
     * The user must keep their face centered in the frame.
     */
    public static final int HOLD_STILL_IN_CENTER = 3;

    /**
     * The user must follow a first set of movement instructions.
     */
    public static final int ENROLLING_MOVEMENT_1 = 4;

    /**
     * The user must follow a second set of movement instructions.
     */
    public static final int ENROLLING_MOVEMENT_2 = 5;

    /**
     * Enrollment has completed. No more action is needed from the user.
     */
    public static final int ENROLLMENT_FINISHED = 6;
}
