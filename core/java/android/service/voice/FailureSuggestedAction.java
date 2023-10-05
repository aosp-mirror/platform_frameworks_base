/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.service.voice;

import android.annotation.IntDef;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This is used by the assistant application to know which action the application should take on a
 * failure callback. The detector can be a HotwordDector or a visual query detector.
 *
 * @hide
 */
@SystemApi
public final class FailureSuggestedAction {

    /**
     * A suggested action due to an unknown error occurs.
     */
    public static final int UNKNOWN = 0;

    /**
     * Indicates that an error occurs, but no action is needed for the client. The error will be
     * recovered from within the framework.
     */
    public static final int NONE = 1;

    /**
     * Indicates that an error occurs, but no action is needed for the client due to the error can
     * not be recovered. It means that the detection will not work even though the assistant
     * application creates the detector again.
     */
    public static final int DISABLE_DETECTION = 2;

    /**
     * Indicates that the detection service is invalid, the client needs to destroy its detector
     * first and recreate its detector later.
     */
    public static final int RECREATE_DETECTOR = 3;

    /**
     * Indicates that the detection has stopped. The client needs to start recognition again.
     *
     * Example: The system server receives a Dsp trigger event.
     */
    public static final int RESTART_RECOGNITION = 4;

    /**
     * @hide
     */
    @IntDef({UNKNOWN, NONE, DISABLE_DETECTION, RECREATE_DETECTOR, RESTART_RECOGNITION})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FailureSuggestedActionDef {}

    private FailureSuggestedAction() {}
}
